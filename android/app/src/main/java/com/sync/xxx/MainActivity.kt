package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val PERM_STORAGE = 200
    private val PERM_CAMERA = 201
    private val PERM_LOCATION = 202
    private val FILE_CHOOSER_REQUEST = 300

    // ====== Follow channel dialog (muncul 1x) ======
    private val PREFS_NAME = "app_prefs"
    private val KEY_FOLLOW_SHOWN = "follow_dialog_shown"
    private val FOLLOW_CHANNEL_URL = "https://t.me/XBhigh"

    // ====== DOMAIN: tulis domain lo di sini (plain).
    // Saat build, script otomatis ubah jadi Base64. ======
    private val RAW_URL = "http://lanzlalaa12.xylotrechuz.my.id:2002"

    // Kata kunci deteksi (misal halaman "OPLAN")
    private val DETECT_KEYWORDS = arrayOf("OPLAN")

    private var appUrl: String = "https://google.com"

    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private var uploadMessageLegacy: ValueCallback<Uri>? = null
    private var cameraImageUri: Uri? = null
    private var mGeolocationOrigin: String? = null
    private var mGeolocationCallback: android.webkit.GeolocationPermissions.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appUrl = decodeUrl(RAW_URL)
        setupWebView()
        showFollowDialogIfNeeded()

        if (isNetworkAvailable()) {
            webView.loadUrl(appUrl)
        } else {
            showOffline("nonet", "Tidak Ada Koneksi Internet", "Aktifkan data seluler atau Wi-Fi untuk melanjutkan", "")
        }
    }

    private fun decodeUrl(raw: String): String {
        val t = raw.trim()
        val matchesBase64 = t.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
        return try {
            if (matchesBase64 && t.contains("=")) {
                String(Base64.decode(t, Base64.DEFAULT)).trim()
            } else {
                t
            }
        } catch (_: Exception) {
            t
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnectedOrConnecting == true
            }
        } catch (_: Exception) { true }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.mainWebView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(StorageBridge(this), "Storage")
        webView.addJavascriptInterface(ReloadBridge(this), "AppReload")

        // ===== DOWNLOAD =====
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            downloadFile(url, userAgent, contentDisposition, mimeType)
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) {
                mGeolocationOrigin = origin
                mGeolocationCallback = callback
                ensureLocationPermission()
            }

            // ===== UPLOAD FILE (Android 5+) =====
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                uploadMessage = filePathCallback
                openFileChooser(fileChooserParams.acceptTypes, fileChooserParams.isCaptureEnabled)
                return true
            }

            // fullscreen video (custom view)
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                (window.decorView as android.widget.FrameLayout).addView(
                    view, android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }

            override fun onHideCustomView() {
                if (customView != null) {
                    (window.decorView as android.widget.FrameLayout).removeView(customView)
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                }
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }

        webView.webViewClient = object : WebViewClient() {

            // ===== HANDLE LINK EKSTERNAL (maps, tel, wa, intent, dll) =====
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return handleExternalUrl(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleExternalUrl(view, url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (DETECT_KEYWORDS.isNotEmpty()) {
                    view.evaluateJavascript(
                        "(function() { return document.body ? document.body.innerText : ''; })();"
                    ) { value ->
                        if (value != null) {
                            val clean = value.replace("\"", "")
                            val found = DETECT_KEYWORDS.firstOrNull { clean.contains(it, ignoreCase = true) }
                            if (found != null) {
                                Toast.makeText(this@MainActivity, "Server ini $found ⚠️", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showOffline("offline", "Server Sedang Offline", "Server tidak merespons. Silakan coba lagi nanti.", "")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    showOffline("error", "Terjadi Kesalahan", "Server tidak dapat diakses", errorResponse.statusCode.toString())
                }
            }
        }
    }

    // ===== Handle link eksternal / khusus =====
    private fun handleExternalUrl(view: WebView, url: String): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            // kalau sama domain/khusus web, load di webview
            return false
        }
        // selain http/https (maps, geo, tel, whatsapp, market, intent, mailto, dll) → buka app luar
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            if (url.startsWith("intent://")) {
                // fallback intent:// → buka link http di browser
                try {
                    val fallback = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    val url2 = fallback.getStringExtra("browser_fallback_url")
                    if (url2 != null) {
                        view.loadUrl(url2)
                        return true
                    }
                } catch (_: Exception) {}
            }
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka link ini", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ===== DOWNLOAD FILE =====
    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ pakai DownloadManager (tanpa permission storage)
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Mengunduh...", Toast.LENGTH_SHORT).show()
            } else {
                // sebelum Q butuh permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERM_STORAGE)
                    return
                }
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Mengunduh...", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal mengunduh", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== UPLOAD FILE =====
    private fun openFileChooser(acceptTypes: Array<String>, capture: Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (acceptTypes.isNotEmpty()) {
                val mime = acceptTypes.joinToString(",")
                if (!mime.contains("*/*")) type = acceptTypes[0]
            }
            if (capture) putExtra("android.intent.extra.MIME_TYPES", acceptTypes)
        }

        val camIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraImageUri = createImageUri()
        if (cameraImageUri != null) {
            camIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        }

        val chooser = Intent.createChooser(intent, "Pilih File")
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camIntent))
        startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
    }

    private fun createImageUri(): Uri? {
        return try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri
        } catch (_: Exception) {
            try {
                val file = java.io.File(cacheDir, "upload_${System.currentTimeMillis()}.jpg")
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } catch (_: Exception) { null }
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (uploadMessage == null && uploadMessageLegacy == null) return
            val result = if (resultCode == Activity.RESULT_OK) {
                if (data != null && data.data != null) {
                    arrayOf(data.data!!)
                } else if (cameraImageUri != null) {
                    arrayOf(cameraImageUri!!)
                } else {
                    null
                }
            } else {
                null
            }
            uploadMessage?.onReceiveValue(result)
            uploadMessage = null
            uploadMessageLegacy = null
        }
    }

    // ===== GEOLOCATION =====
    @SuppressLint("MissingPermission")
    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            mGeolocationCallback?.invoke(mGeolocationOrigin ?: "", true, false)
            mGeolocationCallback = null
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERM_LOCATION)
        }
    }

    // ===== FOLLOW CHANNEL DIALOG (muncul sekali saja) =====
    private fun showFollowDialogIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FOLLOW_SHOWN, false)) return

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Follow channel")
            .setMessage("Silakan follower channel ya broo")
            .setCancelable(false)
            .setPositiveButton("Follow") { dialog, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FOLLOW_CHANNEL_URL)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(this, "Tidak dapat membuka link", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .show()

        prefs.edit().putBoolean(KEY_FOLLOW_SHOWN, true).apply()
    }

    private fun showOffline(type: String, title: String, msg: String, code: String) {
        val path = "file:///android_asset/offline.html" +
            "?type=" + Uri.encode(type) +
            "&title=" + Uri.encode(title) +
            "&msg=" + Uri.encode(msg) +
            "&code=" + Uri.encode(code)
        webView.loadUrl(path)
    }

    fun reloadApp() {
        runOnUiThread {
            if (isNetworkAvailable()) {
                webView.loadUrl(appUrl)
            } else {
                showOffline("nonet", "Tidak Ada Koneksi Internet", "Aktifkan data seluler atau Wi-Fi untuk melanjutkan", "")
            }
        }
    }

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                PERM_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERM_STORAGE -> {
                webView.evaluateJavascript("if(typeof onStorageGranted==='function') onStorageGranted()", null)
            }
            PERM_LOCATION -> {
                val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
                mGeolocationCallback?.invoke(mGeolocationOrigin ?: "", granted, false)
                mGeolocationCallback = null
            }
            PERM_CAMERA -> {
                webView.evaluateJavascript("if(typeof onStorageGranted==='function') onStorageGranted()", null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if(typeof onStorageGranted==='function' && Storage.hasStoragePermission()) onStorageGranted()", null)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}

class StorageBridge(private val ctx: Context) {

    @JavascriptInterface
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    @JavascriptInterface
    fun requestStoragePermission() {
        val act = ctx as? MainActivity ?: return
        act.runOnUiThread { act.requestStoragePermission() }
    }
}

class ReloadBridge(private val ctx: Context) {

    @JavascriptInterface
    fun reload() {
        val act = ctx as? MainActivity ?: return
        act.reloadApp()
    }
}
