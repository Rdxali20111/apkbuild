package com.agentk.selfbuy

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

class MainActivity : AppCompatActivity() {

    private val STORE_URL = "https://flat-wildflower-216b.rdxali20111.workers.dev/"
    private val SUPPORT_EMAIL = "support@selfbuy.com"

    private lateinit var webView: WebView
    private lateinit var layout2fa: View
    private lateinit var layoutSupport: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var attachedScreenshotUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentOtp = ""

    private val webFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            filePathCallback?.onReceiveValue(arrayOf(uri))
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val supportImgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            attachedScreenshotUri = uri
            findViewById<TextView>(R.id.tvAttachedFile).text = "Screenshot attached ✓"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.storeWebView)
        layout2fa = findViewById(R.id.layout2fa)
        layoutSupport = findViewById(R.id.layoutSupport)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                webFilePicker.launch("image/*")
                return true
            }
        }
        webView.loadUrl(STORE_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            webView.visibility = View.GONE
            layout2fa.visibility = View.GONE
            layoutSupport.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_store -> webView.visibility = View.VISIBLE
                R.id.nav_2fa -> layout2fa.visibility = View.VISIBLE
                R.id.nav_support -> layoutSupport.visibility = View.VISIBLE
            }
            true
        }

        val etSecret = findViewById<EditText>(R.id.etSecretKey)
        val tvOtp = findViewById<TextView>(R.id.tvOtpCode)
        val tvTimer = findViewById<TextView>(R.id.tvTimer)

        findViewById<Button>(R.id.btnCopyOtp).setOnClickListener {
            if (currentOtp.length == 6) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("2FA OTP", currentOtp))
                Toast.makeText(this, "OTP Copied: $currentOtp", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Pehle valid 2FA key paste karein", Toast.LENGTH_SHORT).show()
            }
        }

        val otpRunnable = object : Runnable {
            override fun run() {
                val secret = etSecret.text.toString().trim()
                if (secret.isNotEmpty()) {
                    val code = generateTOTP(secret)
                    if (code != null) {
                        currentOtp = code
                        tvOtp.text = "${code.substring(0, 3)} ${code.substring(3)}"
                        val secLeft = 30 - ((System.currentTimeMillis() / 1000L) % 30)
                        tvTimer.text = "Expires in: ${secLeft}s"
                    } else {
                        currentOtp = ""
                        tvOtp.text = "INVALID"
                        tvTimer.text = "Invalid Base32 Key"
                    }
                } else {
                    currentOtp = ""
                    tvOtp.text = "------"
                    tvTimer.text = "Expires in: --s"
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(otpRunnable)

        findViewById<Button>(R.id.btnAttachSS).setOnClickListener {
            supportImgPicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnSendSupport).setOnClickListener {
            val subject = findViewById<EditText>(R.id.etSupSubject).text.toString().trim()
            val message = findViewById<EditText>(R.id.etSupMessage).text.toString().trim()
            if (subject.isEmpty() || message.isEmpty()) {
                Toast.makeText(this, "Subject aur Message dono likhein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (attachedScreenshotUri != null) "image/*" else "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "[SelfBuy Support] $subject")
                putExtra(Intent.EXTRA_TEXT, message)
                if (attachedScreenshotUri != null) {
                    putExtra(Intent.EXTRA_STREAM, attachedScreenshotUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            try {
                startActivity(Intent.createChooser(intent, "Send Support Email"))
            } catch (e: Exception) {
                Toast.makeText(this, "No email app found on device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateTOTP(base32Secret: String): String? {
        return try {
            val key = decodeBase32(base32Secret) ?: return null
            val timeStep = System.currentTimeMillis() / 1000L / 30L
            val data = ByteBuffer.allocate(8).putLong(timeStep).array()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)
            val offset = (hash[hash.size - 1] and 0x0F).toInt()
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 1_000_000
            String.format("%06d", otp)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase32(input: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input.uppercase().replace(" ", "").replace("-", "").replace("=", "")
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in clean) {
            val valIdx = alphabet.indexOf(c)
            if (valIdx < 0) return null
            buffer = (buffer shl 5) or valIdx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
