package com.igapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Splash stays on screen until the first page is ready (or 3s max, e.g. offline)
    @Volatile private var keepSplash = true

    // Native loading cover (logo + 3 blinking dots) shown until the home feed is really drawn
    private var loaderView: FrameLayout? = null
    private var loaderHidden = false
    private val dotAnimators = mutableListOf<ObjectAnimator>()

    // Holds the WebView's callback while the system file picker is open (for uploads)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* if denied, the relevant action (upload/download/mic) just won't work until retried */ }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { keepSplash }
        Handler(Looper.getMainLooper()).postDelayed({ keepSplash = false }, 3000)

        super.onCreate(savedInstanceState)

        applyStatusBarColor()
        requestNeededPermissions()

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#0C1014")) // no white flash before the page paints
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0C1014"))
        root.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Loading cover: sits under the system splash, so when the splash lifts you see the same logo
        // with three blinking dots. Status + navigation bar are black too, matching the cover.
        val loader = buildLoader()
        loaderView = loader
        root.addView(loader, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setBarColors(Color.BLACK)
        keepSplash = false   // the cover is ready, let the system splash hand over to it right away
        // Safety net: never keep the cover up for more than 9 seconds.
        webView.postDelayed({ hideLoader() }, 9000)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Desktop site mode: Instagram's mobile web profile header (followers / following / bio)
        // vanishes after a moment, but the desktop version loads it fine. Same as Chrome's "Desktop site".
        val chromeMajor = Regex("Chrome/(\\d+)").find(settings.userAgentString)?.groupValues?.get(1) ?: "120"
        settings.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.0.0 Safari/537.36"

        // Always dark: tell the web page to use its dark theme (prefers-color-scheme: dark).
        // Works on every Android version, including Android 9 (the WebView itself is what matters).
        applyWebDarkMode(settings)
        warnIfWebViewOutdated()

        // Some devices need audio mode explicitly reset to NORMAL for WebView mic capture to work
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.mode = AudioManager.MODE_NORMAL

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(LoaderBridge(), "AndroidLoader")

        webView.webViewClient = object : WebViewClient() {
            // Share buttons (WhatsApp, Messenger, Facebook, SMS...) use links WebView can't open itself.
            // Send those to the real app instead of showing "Webpage not available".
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleExternalUrl(request.url)

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { handleExternalUrl(Uri.parse(it)) } ?: false

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                view.evaluateJavascript(FIT_SCREEN_JS, null)
                if (!loaderHidden) view.evaluateJavascript(FEED_READY_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(FIT_SCREEN_JS, null)
                view.evaluateJavascript(FORCE_DARK_JS, null)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
                keepSplash = false
                if (!loaderHidden) {
                    view.evaluateJavascript(FEED_READY_JS, null)
                    // Any page other than the home feed (login, a shared link...) has no feed to wait for.
                    if (!isHomeUrl(url)) view.postDelayed({ hideLoader() }, 500)
                }
            }
        }

        // Lets the upload/attach button inside instagram.com open the system file picker
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"))
                return true
            }

            // Grants mic (and camera, if requested) access for voice notes / calls,
            // as long as the matching Android runtime permission has already been granted.
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val granted = request.resources.filter { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        else -> false
                    }
                }
                if (granted.isNotEmpty()) {
                    runOnUiThread { request.grant(granted.toTypedArray()) }
                } else {
                    runOnUiThread { request.deny() }
                }
            }
        }

        // Handles files that Instagram pushes out for download (photos, videos, reels, etc.)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val cookie = CookieManager.getInstance().getCookie(url) ?: ""

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("cookie", cookie)
                    setMimeType(mimeType)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }

                getSystemService<DownloadManager>()?.enqueue(request)
                Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(resolveStartUrl(intent))
        }
    }

    /** Asks for the storage/media/mic permissions needed for upload, download & voice notes. */
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        needed += Manifest.permission.RECORD_AUDIO

        val notGranted = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    /**
     * Returns true if the link was handed to another app (so WebView must not load it),
     * false if the WebView should load it normally.
     */
    private fun handleExternalUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        when (scheme) {
            "http", "https" -> {
                val host = uri.host?.lowercase() ?: return false
                val path = uri.path ?: ""
                val isFacebook = host == "facebook.com" || host.endsWith(".facebook.com")
                val isTwitter = host == "twitter.com" || host == "x.com" || host.endsWith(".twitter.com")
                val external =
                    host == "wa.me" || host == "whatsapp.com" || host.endsWith(".whatsapp.com") ||
                    host == "m.me" || host == "t.me" || host == "telegram.me" ||
                    (isFacebook && (path.startsWith("/sharer") || path.startsWith("/share") ||
                        path.startsWith("/dialog/share") || path.startsWith("/dialog/send") ||
                        path.startsWith("/send"))) ||
                    (isTwitter && path.startsWith("/intent"))
                if (!external) return false
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            // pages' own internal schemes stay inside the WebView
            "about", "blob", "data", "javascript", "file", "content" -> return false
            "intent" -> {
                try {
                    val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                        addCategory(Intent.CATEGORY_BROWSABLE)
                        component = null
                        selector = null
                    }
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    val fallback = try {
                        Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                            .getStringExtra("browser_fallback_url")
                    } catch (e2: Exception) { null }
                    if (!fallback.isNullOrEmpty()) {
                        openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                    } else {
                        Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Can't open this link", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            // whatsapp:, fb-messenger:, fb:, sms:, smsto:, mailto:, tel:, market:, tg: ...
            else -> {
                openExternal(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
        }
    }

    private fun openExternal(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this link", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWebDarkMode(settings: WebSettings) {
        try {
            when {
                // Android 13+ WebView
                WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                // Android 5-12 (incl. Android 9): updatable WebView 76+
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) -> {
                    WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                        // use Instagram's own dark theme, don't invert colours
                        WebSettingsCompat.setForceDarkStrategy(
                            settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                        )
                    }
                }
            }
        } catch (e: Exception) { /* dark mode still comes from ?theme=dark */ }
    }

    /** Old phones (e.g. Android 9) often have an outdated WebView, which Instagram web needs to be recent. */
    private fun warnIfWebViewOutdated() {
        try {
            val major = WebViewCompat.getCurrentWebViewPackage(this)
                ?.versionName?.substringBefore('.')?.toIntOrNull() ?: return
            if (major < 100) {
                Toast.makeText(
                    this,
                    "Please update \"Android System WebView\" from Play Store for the best Instagram experience",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) { }
    }

    /** Link opened from another app (WhatsApp, Messenger, browser...) while the app is already running. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.data != null) {
            webView.loadUrl(resolveStartUrl(intent))
        }
    }

    /** Returns the shared Instagram link if the app was opened from one, otherwise the home page. */
    private fun resolveStartUrl(intent: Intent?): String {
        val data = intent?.data
        val host = data?.host?.lowercase()
        val isInstagramLink = intent?.action == Intent.ACTION_VIEW &&
            host != null &&
            (host == "instagram.com" || host.endsWith(".instagram.com") ||
             host == "instagr.am" || host.endsWith(".instagr.am") || host == "ig.me")
        if (!isInstagramLink) return HOME_URL

        val builder = data!!.buildUpon().scheme("https")
        if (data.getQueryParameter("theme") == null) builder.appendQueryParameter("theme", "dark")
        return builder.build().toString()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = color
        }
    }

    /** True for the Instagram home feed (where we wait for posts to draw). */
    private fun isHomeUrl(url: String?): Boolean {
        val u = url?.let { Uri.parse(it) } ?: return false
        val host = u.host?.lowercase() ?: return false
        if (!(host == "instagram.com" || host.endsWith(".instagram.com"))) return false
        return (u.path ?: "").trimEnd('/').isEmpty()
    }

    /** Splash-style cover: Instagram glyph with three softly blinking dots underneath. */
    private fun buildLoader(): FrameLayout {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.BLACK)
        overlay.isClickable = true      // swallow touches while the page is still loading
        overlay.isFocusable = true

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_splash_vector)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        overlay.addView(logo, FrameLayout.LayoutParams(dp(288), dp(288), Gravity.CENTER))

        val dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        for (i in 0..2) {
            val dot = View(this)
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(Color.parseColor("#E1306C"))
            dot.background = shape
            dot.alpha = 0.2f
            val lp = LinearLayout.LayoutParams(dp(9), dp(9))
            lp.marginStart = dp(6)
            lp.marginEnd = dp(6)
            dots.addView(dot, lp)

            val anim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.2f, 1f, 0.2f)
            anim.duration = 1050
            anim.startDelay = i * 170L
            anim.repeatCount = ValueAnimator.INFINITE
            anim.interpolator = LinearInterpolator()
            dotAnimators.add(anim)
        }
        overlay.addView(
            dots,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        dots.translationY = dp(70).toFloat()   // just below the glyph
        dotAnimators.forEach { it.start() }
        return overlay
    }

    /** Fades the cover out (and the bars from black to the normal page colour); safe to call many times. */
    private fun hideLoader() {
        if (loaderHidden) return
        loaderHidden = true
        val v = loaderView ?: return
        ValueAnimator.ofArgb(Color.BLACK, Color.parseColor("#0C1014")).apply {
            duration = 220
            addUpdateListener { setBarColors(it.animatedValue as Int) }
            start()
        }
        v.animate().alpha(0f).setDuration(220).withEndAction {
            dotAnimators.forEach { it.cancel() }
            dotAnimators.clear()
            (v.parent as? ViewGroup)?.removeView(v)
            loaderView = null
        }.start()
    }

    private inner class LoaderBridge {
        @JavascriptInterface
        fun ready() {
            runOnUiThread { hideLoader() }
        }
    }

    private fun applyStatusBarColor() {
        val barColor = Color.parseColor("#0C1014") // exactly Instagram dark mode page background

        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        window.decorView.setBackgroundColor(barColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = barColor
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // stop Android from adding its own translucent scrim behind the bars
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val HOME_URL = "https://www.instagram.com/?theme=dark"

        // Tells the app when the page is really ready (a post / big images drawn, or a login form),
        // so the native loading cover can be removed.
        private const val FEED_READY_JS = """
            (function() {
                if (window.__igReadyWatch) return;
                window.__igReadyWatch = true;
                var t0 = Date.now(), fired = false;
                function bigImagesShown() {
                    var imgs = document.images, n = 0;
                    for (var i = 0; i < imgs.length; i++) {
                        var im = imgs[i];
                        if (!im.complete || im.naturalWidth < 120) continue;
                        var r = im.getBoundingClientRect();
                        if (r.width > 120 && r.height > 80 && r.bottom > 0 && r.top < window.innerHeight) n++;
                    }
                    return n >= 2;
                }
                function isReady() {
                    if (document.querySelector('input[type="password"]')) return true;   // login page
                    if (document.querySelector('article')) return true;                  // a post is drawn
                    return bigImagesShown();                                             // photos drawn
                }
                var timer = setInterval(function() {
                    if (fired) return;
                    var ok = false;
                    try { ok = isReady(); } catch (e) {}
                    if (ok || Date.now() - t0 > 7000) {
                        fired = true;
                        clearInterval(timer);
                        // one more frame so the feed is really painted before the cover is lifted
                        requestAnimationFrame(function() {
                            setTimeout(function() { try { AndroidLoader.ready(); } catch (e) {} }, 120);
                        });
                    }
                }, 120);
            })();
        """

        // Desktop layout fitted to the phone screen: a fixed 800px-wide viewport (Instagram keeps its
        // desktop layout above ~770px). Lower the number = bigger content, but under ~770 Instagram
        // flips back to the mobile layout (the one that loses the profile header).
        private const val FIT_SCREEN_JS = """
            (function() {
                function apply() {
                    try {
                        var m = document.querySelector('meta[name="viewport"]');
                        if (!m) {
                            m = document.createElement('meta');
                            m.name = 'viewport';
                            (document.head || document.documentElement).appendChild(m);
                        }
                        var want = 'width=800, user-scalable=yes';
                        if (m.getAttribute('content') !== want) m.setAttribute('content', want);
                    } catch (e) {}
                }
                apply();
                if (window.__fitWatch) return;
                window.__fitWatch = true;
                try {
                    new MutationObserver(apply).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
                } catch (e) {}
            })();
        """

        // Makes sure Instagram web stays in dark mode and native bits (scrollbars, inputs) are dark too.
        private const val FORCE_DARK_JS = """
            (function() {
                try {
                    document.documentElement.style.setProperty('color-scheme', 'dark');
                } catch (e) {}
            })();
        """

        // Continuously watches the page for "Open app" / "Get the app"
        // style native-app install prompts and hides them as they appear,
        // including ones added later while scrolling. Matches by visible
        // text/aria-label instead of CSS class names, since Instagram's
        // class names change often but the button's text stays similar.
        private const val HIDE_OPEN_APP_JS = """
            (function() {
                if (window.__openAppHiderInstalled) return;
                window.__openAppHiderInstalled = true;

                var phrases = ['open app', 'get the app', 'open in app', 'install app'];

                function isOpenAppButton(el) {
                    if (!el || el.nodeType !== 1) return false;
                    var label = (el.getAttribute('aria-label') || '').trim().toLowerCase();
                    var text = (el.textContent || '').trim().toLowerCase();
                    for (var i = 0; i < phrases.length; i++) {
                        if (label === phrases[i] || text === phrases[i]) return true;
                    }
                    return false;
                }

                function hideIfMatch(el) {
                    if (!el || el.nodeType !== 1) return;
                    if (isOpenAppButton(el)) {
                        var target = el;
                        for (var i = 0; i < 4 && target.parentElement; i++) {
                            target = target.parentElement;
                        }
                        target.style.setProperty('display', 'none', 'important');
                    }
                }

                function scan(root) {
                    try {
                        if (isOpenAppButton(root)) {
                            hideIfMatch(root);
                            return;
                        }
                        var all = root.querySelectorAll('div,a,span,button');
                        for (var i = 0; i < all.length; i++) {
                            hideIfMatch(all[i]);
                        }
                    } catch (e) {}
                }

                scan(document.body);

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var added = mutations[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            scan(added[j]);
                        }
                    }
                });

                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """
    }
}
