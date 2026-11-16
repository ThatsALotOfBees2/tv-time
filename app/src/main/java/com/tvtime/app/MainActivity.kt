package com.tvtime.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var fullscreen: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var cursor: CursorView
    private lateinit var osdView: TextView
    private lateinit var menu: LinearLayout
    private lateinit var menuItems: LinearLayout
    private lateinit var menuUrl: TextView

    private lateinit var prefs: SharedPreferences
    private lateinit var adBlocker: AdBlocker
    private lateinit var injectJs: String
    private var docStartScript = false
    private var webViewDead = false

    private val handler = Handler(Looper.getMainLooper())

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var playerMode = false

    /** Site keys (see [AdBlocker.siteKey]) the main page is allowed to navigate to. */
    private val allowedSites = HashSet<String>()

    private lateinit var mobileUa: String
    private lateinit var desktopUa: String

    @Volatile
    private var adblockOn = true
    private var strictOn = true

    // ---------- lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webview)
        fullscreen = findViewById(R.id.fullscreen)
        progress = findViewById(R.id.progress)
        cursor = findViewById(R.id.cursor)
        osdView = findViewById(R.id.osd)
        menu = findViewById(R.id.menu)
        menuItems = findViewById(R.id.menu_items)
        menuUrl = findViewById(R.id.menu_url)

        prefs = getSharedPreferences("tvtime", MODE_PRIVATE)
        adblockOn = prefs.getBoolean(PREF_ADBLOCK, true)
        strictOn = prefs.getBoolean(PREF_STRICT, true)

        injectJs = assets.open("inject.js").bufferedReader().use { it.readText() } + "\n" +
            assets.open("shield/youtube.js").bufferedReader().use { it.readText() }

        adBlocker = AdBlocker(this)
        refreshHomeSite()
        adBlocker.load()

        setupWebView()
        setupCursor()
        buildMenu()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(homeUrl())
        }
        webView.requestFocus()
        cursor.post { cursor.showCursor() }
        osd(getString(R.string.app_name) + "  -  hold OK for menu", 3500)
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDead) webView.onResume()
        goImmersive()
    }

    override fun onPause() {
        if (!webViewDead) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!webViewDead) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDead) webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ---------- home / sites ----------

    private fun homeUrl(): String =
        prefs.getString(PREF_HOME, null) ?: getString(R.string.default_home_url)

    private fun refreshHomeSite() {
        allowedSites.addAll(DEFAULT_SITES)
        Uri.parse(homeUrl()).host?.let { allowedSites.add(AdBlocker.siteKey(it)) }
        adBlocker.neverBlockKeys = allowedSites.toSet()
    }

    /** The built-in TV Time start page (shared with the Raspberry Pi version, see web/start). */
    private fun onStartPage(): Boolean =
        customView == null && webView.url?.startsWith(START_PAGE_PREFIX) == true

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        val base = WebSettings.getDefaultUserAgent(this)
        mobileUa = base.replace("; wv", "").replace(Regex("Version/[\\d.]+ "), "")
        val chrome = Regex("Chrome/([\\d.]+)").find(base)?.groupValues?.get(1) ?: "124.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chrome Safari/537.36"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)          // so pop-ups come to onCreateWindow, where we drop them
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            userAgentString = if (prefs.getBoolean(PREF_DESKTOP, false)) desktopUa else mobileUa
        }
        webView.setBackgroundColor(0xFF0E0F14.toInt())
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Run our helper script at document start in the page AND every iframe (video players).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, injectJs, setOf("*"))
                docStartScript = true
            } catch (e: Exception) {
                docStartScript = false
            }
        }

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adblockOn && !request.isForMainFrame && adBlocker.shouldBlock(request.url)) {
                return adBlocker.emptyResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            blockNavigation(request.url, request.isForMainFrame)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            blockNavigation(Uri.parse(url), true)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!docStartScript) view.evaluateJavascript(injectJs, null)
            menuUrl.text = url ?: ""
            // The start page is driven with arrow keys; everywhere else the pointer is used.
            if (url?.startsWith(START_PAGE_PREFIX) == true) cursor.hideCursor()
            else if (!playerMode && menu.visibility != View.VISIBLE) cursor.showCursor()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!docStartScript) view.evaluateJavascript(injectJs, null)
            progress.visibility = View.GONE
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(request.url.toString(), error.description?.toString())
        }

        @Deprecated("Deprecated in Java")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
            showError(failingUrl ?: homeUrl(), description)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed (usually out of memory on small TV boxes): start over cleanly.
            webViewDead = true
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    /** Returns true when a navigation must NOT happen. */
    private fun blockNavigation(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("about", "data", "blob", "javascript")) return false
        if (scheme == "file" && uri.toString().startsWith(START_PAGE_PREFIX)) return false
        // Tiles on the start page are sites you chose yourself: always allowed.
        if (mainFrame && onStartPage() && (scheme == "http" || scheme == "https")) {
            uri.host?.let { allowedSites.add(AdBlocker.siteKey(it)) }
            return false
        }
        if (scheme != "http" && scheme != "https") {
            // intent://, market://, tg:// ... on these sites they are always ads trying to open other apps
            osd("Blocked link to another app")
            return true
        }
        if (adblockOn && adBlocker.shouldBlock(uri)) {
            if (mainFrame) osd("Blocked ad: ${uri.host}")
            adBlocker.blockedCount.incrementAndGet()
            return true
        }
        if (mainFrame && strictOn && AdBlocker.siteKey(uri.host) !in allowedSites) {
            osd("Blocked redirect to ${uri.host}")
            return true
        }
        return false
    }

    private fun showError(url: String, description: String?) {
        val safeUrl = url.replace("\"", "%22").replace("<", "%3C")
        val desc = (description ?: "").replace("<", "&lt;")
        val html = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="background:#0e0f14;color:#fff;font-family:sans-serif;display:flex;
              align-items:center;justify-content:center;height:100vh;margin:0;text-align:center">
            <div><h1 style="color:#e50914">Can't load this page</h1>
            <p style="color:#a3a7b5">$desc</p>
            <a href="$safeUrl" style="display:inline-block;margin-top:24px;padding:16px 40px;background:#e50914;
              color:#fff;border-radius:10px;text-decoration:none;font-size:22px">Try again</a></div>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", url)
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            progress.progress = newProgress
        }

        // Every pop-up / new window is refused.
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message,
        ): Boolean {
            adBlocker.blockedCount.incrementAndGet()
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            leaveFullscreen(notifyWebView = false)
        }

        override fun getDefaultVideoPoster(): Bitmap =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    // ---------- fullscreen video ----------

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreen.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreen.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        setPlayerMode(true)
        goImmersive()
    }

    private fun leaveFullscreen(notifyWebView: Boolean) {
        val view = customView ?: return
        val callback = customCallback
        customView = null
        customCallback = null
        fullscreen.removeView(view)
        fullscreen.visibility = View.GONE
        webView.visibility = View.VISIBLE
        if (notifyWebView) callback?.onCustomViewHidden()
        setPlayerMode(false)
        webView.requestFocus()
    }

    // ---------- pointer ----------

    private var lastHover = 0L
    private var scrollX = 0f
    private var scrollY = 0f
    private var scrollPending = false

    private fun setupCursor() {
        cursor.onMoved = { x, y ->
            val now = SystemClock.uptimeMillis()
            if (now - lastHover > 60) {
                lastHover = now
                hover(x, y)
            }
        }
        cursor.onEdgePush = { dx, dy ->
            scrollX += dx * 1.3f
            scrollY += dy * 1.3f
            if (!scrollPending) {
                scrollPending = true
                handler.postDelayed(flushScroll, 33)
            }
        }
    }

    private val flushScroll = Runnable {
        scrollPending = false
        val dx = scrollX
        val dy = scrollY
        scrollX = 0f
        scrollY = 0f
        if (customView != null || webViewDead) return@Runnable
        @Suppress("DEPRECATION")
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        val x = (cursor.cursorX / scale).toInt()
        val y = (cursor.cursorY / scale).toInt()
        val sx = (dx / scale).toInt()
        val sy = (dy / scale).toInt()
        webView.evaluateJavascript(
            "window.__tvtimeScroll ? window.__tvtimeScroll($x,$y,$sx,$sy) : window.scrollBy($sx,$sy)",
            null,
        )
    }

    private fun touchTarget(): View = if (customView != null) fullscreen else webView

    /** Synthesises a finger tap at the pointer, which works inside any iframe / video player. */
    private fun tap(x: Float, y: Float) {
        val target = touchTarget()
        if (target === webView && !webView.hasFocus()) webView.requestFocus()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    /** Mouse-hover so menus that open on hover work too. */
    private fun hover(x: Float, y: Float) {
        val target = touchTarget()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0f
            size = 1f
        })
        val t = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A real finger/mouse is being used (phone, tablet, air-mouse): get the pointer out of the way.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && cursor.cursorVisible) cursor.hideCursor()
        return super.dispatchTouchEvent(ev)
    }

    // ---------- player mode ----------

    private fun setPlayerMode(on: Boolean) {
        playerMode = on
        if (on) {
            cursor.hideCursor()
            osd("Player controls:  < > seek 10s   OK play/pause   ^ pointer   BACK exit", 4000)
        } else {
            cursor.showCursor()
        }
    }

    private fun media(cmd: String) {
        if (webViewDead) return
        webView.evaluateJavascript("window.__tvtimeMedia && window.__tvtimeMedia('$cmd')", null)
    }

    // ---------- remote control ----------

    private var longPressKey = -1
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        showMenu()
    }
    private var lastBack = 0L
    private var seekAccum = 0

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        code == KeyEvent.KEYCODE_BUTTON_A

    /** Short press -> [short]; holding for 0.65 s opens the menu instead. */
    private fun longPressable(event: KeyEvent, short: () -> Unit): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longPressKey = event.keyCode
                longPressFired = false
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, 650)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressKey == event.keyCode && !longPressFired) short()
                longPressKey = -1
            }
        }
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // Swallow the rest of a key press that already opened the menu.
        if (longPressFired && code == longPressKey) {
            if (event.action == KeyEvent.ACTION_UP) {
                longPressFired = false
                longPressKey = -1
            }
            return true
        }

        if (menu.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_ESCAPE) {
                if (!down) hideMenu()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_BUTTON_START -> {
                if (!down) showMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B ->
                return longPressable(event) { onBackKey() }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_SPACE -> {
                if (code == KeyEvent.KEYCODE_SPACE && !playerMode) return super.dispatchKeyEvent(event)
                if (down && event.repeatCount == 0) media("toggle")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) media("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { if (down) media("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (down) seek(30); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) seek(-30); return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) webView.pageUp(false); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) webView.pageDown(false); return true
            }
        }

        if (playerMode) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down && event.repeatCount % 2 == 0) seek(-10); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && event.repeatCount % 2 == 0) seek(10); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && event.repeatCount == 0) {
                        playerMode = false
                        cursor.showCursor()
                        osd("Pointer mode", 1200)
                    }
                    return true
                }
            }
            if (isOk(code)) return longPressable(event) { media("toggle") }
            return super.dispatchKeyEvent(event)
        }

        if (onStartPage()) {
            // Arrow keys go to the page (it moves the highlight between tiles); OK = Enter.
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    cursor.hideCursor()
                    if (!webView.hasFocus()) webView.requestFocus()
                    return super.dispatchKeyEvent(event)
                }
            }
            if (isOk(code)) {
                return longPressable(event) {
                    if (!webView.hasFocus()) webView.requestFocus()
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }

        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> null
        }
        if (dir != null) {
            if (down) cursor.press(dir.first, dir.second) else cursor.release(dir.first, dir.second)
            return true
        }
        if (isOk(code)) {
            return longPressable(event) {
                cursor.showCursor()
                tap(cursor.cursorX, cursor.cursorY)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private val clearSeek = Runnable { seekAccum = 0 }

    private fun seek(seconds: Int) {
        media("seek:$seconds")
        seekAccum += seconds
        handler.removeCallbacks(clearSeek)
        handler.postDelayed(clearSeek, 1500)
        osd((if (seekAccum >= 0) ">> +" else "<< ") + seekAccum + "s", 1200)
    }

    private fun onBackKey() {
        when {
            customView != null -> leaveFullscreen(notifyWebView = true)
            playerMode -> setPlayerMode(false)
            webView.canGoBack() -> webView.goBack()
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBack < 2500) finish()
                else {
                    lastBack = now
                    osd("Press BACK again to exit  (hold BACK for menu)", 2500)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackKey()
    }

    // ---------- on-screen messages ----------

    private val hideOsd = Runnable { osdView.visibility = View.GONE }

    private fun osd(text: String, ms: Long = 1800) {
        osdView.text = text
        osdView.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    // ---------- menu ----------

    private class Item(val label: () -> String, val action: () -> Unit)

    private val items: List<Item> by lazy {
        listOf(
            Item({ "Home" }) { hideMenu(); webView.loadUrl(homeUrl()) },
            Item({ "Start page (Streamex, YouTube, your sites)" }) { hideMenu(); webView.loadUrl(START_PAGE) },
            Item({ "Back" }) { hideMenu(); if (webView.canGoBack()) webView.goBack() },
            Item({ "Forward" }) { hideMenu(); if (webView.canGoForward()) webView.goForward() },
            Item({ "Reload" }) { hideMenu(); webView.reload() },
            Item({ "Go to address / search..." }) { askAddress() },
            Item({ "Player controls mode" }) { hideMenu(); setPlayerMode(true) },
            Item({ "Zoom in" }) { webView.zoomIn() },
            Item({ "Zoom out" }) { webView.zoomOut() },
            Item({ "Ad blocker: " + (if (adblockOn) "ON  (${adBlocker.blockedCount.get()} blocked)" else "OFF") }) {
                adblockOn = !adblockOn
                prefs.edit().putBoolean(PREF_ADBLOCK, adblockOn).apply()
                refreshMenu()
            },
            Item({ "Block redirects to other sites: " + if (strictOn) "ON" else "OFF" }) {
                strictOn = !strictOn
                prefs.edit().putBoolean(PREF_STRICT, strictOn).apply()
                refreshMenu()
            },
            Item({ "Desktop version of sites: " + if (prefs.getBoolean(PREF_DESKTOP, false)) "ON" else "OFF" }) {
                val on = !prefs.getBoolean(PREF_DESKTOP, false)
                prefs.edit().putBoolean(PREF_DESKTOP, on).apply()
                webView.settings.userAgentString = if (on) desktopUa else mobileUa
                hideMenu()
                webView.reload()
            },
            Item({ "Make this page my Home" }) {
                webView.url?.takeIf { it.startsWith("http") }?.let {
                    prefs.edit().putString(PREF_HOME, it).apply()
                    refreshHomeSite()
                    osd("Home set")
                }
                hideMenu()
            },
            Item({ "Clear cookies & cache" }) {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                hideMenu()
                webView.reload()
                osd("Browsing data cleared")
            },
            Item({ "Exit TV Time" }) { finish() },
        )
    }

    private val itemViews = ArrayList<TextView>()

    private fun buildMenu() {
        val margin = (6 * resources.displayMetrics.density).toInt()
        items.forEachIndexed { i, item ->
            val tv = TextView(this, null, 0, R.style.MenuItem)
            tv.id = View.generateViewId()
            tv.text = item.label()
            tv.isFocusable = true
            tv.isClickable = true
            tv.setOnClickListener { item.action() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = if (i == 0) 0 else margin
            menuItems.addView(tv, lp)
            itemViews.add(tv)
        }
        // Keep D-pad focus trapped inside the menu.
        itemViews.forEachIndexed { i, tv ->
            tv.nextFocusLeftId = tv.id
            tv.nextFocusRightId = tv.id
            tv.nextFocusUpId = itemViews[if (i == 0) itemViews.size - 1 else i - 1].id
            tv.nextFocusDownId = itemViews[if (i == itemViews.size - 1) 0 else i + 1].id
        }
    }

    private fun refreshMenu() {
        items.forEachIndexed { i, item -> itemViews[i].text = item.label() }
        menuUrl.text = webView.url ?: ""
    }

    private fun showMenu() {
        handler.removeCallbacks(longPressRunnable)
        cursor.releaseAll()
        cursor.hideCursor()
        refreshMenu()
        menu.visibility = View.VISIBLE
        itemViews.firstOrNull()?.requestFocus()
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        if (customView == null) webView.requestFocus()
        if (!playerMode) cursor.showCursor()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            setText(webView.url ?: "")
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Go to address or search")
            .setView(input)
            .setPositiveButton("Go") { _, _ -> openTyped(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss()
                openTyped(input.text.toString())
                true
            } else false
        }
        hideMenu()
        dialog.show()
    }

    private fun openTyped(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        // Anything you type yourself is trusted for redirects.
        allowedSites.add(AdBlocker.siteKey(Uri.parse(url).host))
        adBlocker.neverBlockKeys = allowedSites.toSet()
        webView.loadUrl(url)
    }

    companion object {
        private const val START_PAGE = "file:///android_asset/start/index.html"
        private const val START_PAGE_PREFIX = "file:///android_asset/start/"
        private val DEFAULT_SITES = listOf("streamex", "youtube", "youtu", "youtube-nocookie", "google")
        private const val PREF_HOME = "home_url"
        private const val PREF_ADBLOCK = "adblock"
        private const val PREF_STRICT = "strict"
        private const val PREF_DESKTOP = "desktop"
    }
}
