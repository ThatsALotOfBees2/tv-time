package com.tvtime.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/**
 * TV Time: a TV-remote-friendly browser built on Firefox's engine (GeckoView) with
 * uBlock Origin built in for ad blocking, plus TV Time's own helper extension that carries the
 * start page and the remote-control bridge for video players.
 */
class MainActivity : Activity() {

    private lateinit var geckoView: GeckoView
    private lateinit var progress: ProgressBar
    private lateinit var cursor: CursorView
    private lateinit var osdView: TextView
    private lateinit var menu: LinearLayout
    private lateinit var menuItems: LinearLayout
    private lateinit var menuUrl: TextView
    private lateinit var updateView: View
    private lateinit var updateText: TextView
    private lateinit var updateHint: TextView
    private lateinit var updateProgress: ProgressBar

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var runtime: GeckoRuntime
    private var session: GeckoSession? = null
    private var helper: WebExtension? = null
    private var ublock: WebExtension? = null
    /** moz-extension://<id>/start/index.html once the helper extension is installed. */
    private var startPage: String? = null

    private var currentUrl: String = ""
    private var canGoBack = false
    private var canGoForward = false
    private var inFullscreen = false
    private var playerMode = false

    /** Open connections to the helper's content script, one per frame of the current page. */
    private val ports = ArrayList<WebExtension.Port>()

    /** Site keys (see [SiteKey]) the main page is allowed to navigate to. */
    private val allowedSites = HashSet<String>()

    private var adblockOn = true
    private var strictOn = true

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        geckoView = findViewById(R.id.gecko)
        progress = findViewById(R.id.progress)
        cursor = findViewById(R.id.cursor)
        osdView = findViewById(R.id.osd)
        menu = findViewById(R.id.menu)
        menuItems = findViewById(R.id.menu_items)
        menuUrl = findViewById(R.id.menu_url)
        updateView = findViewById(R.id.update)
        updateText = findViewById(R.id.update_text)
        updateHint = findViewById(R.id.update_hint)
        updateProgress = findViewById(R.id.update_progress)
        updateView.setOnClickListener { onUpdateOk() }

        prefs = getSharedPreferences("tvtime", MODE_PRIVATE)
        adblockOn = prefs.getBoolean(PREF_ADBLOCK, true)
        strictOn = prefs.getBoolean(PREF_STRICT, true)
        refreshHomeSite()

        runtime = TvTimeApp.runtime(this)
        openSession()
        setupCursor()
        buildMenu()
        installExtensionsThenLoad()

        geckoView.requestFocus()
        cursor.post { cursor.hideCursor() }
        osd(getString(R.string.app_name) + "  -  hold OK for menu", 3500)
        checkForUpdate()
    }

    override fun onResume() {
        super.onResume()
        session?.setActive(true)
        goImmersive()
        onUpdateResume()
    }

    override fun onPause() {
        session?.setActive(false)
        if (updateState == UpdateState.INSTALLING) installerShown = true
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        geckoView.releaseSession()
        session?.close()
        session = null
        super.onDestroy()
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

    // ---------- engine ----------

    private fun openSession() {
        val desktop = prefs.getBoolean(PREF_DESKTOP, false)
        val s = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build(),
        )
        s.navigationDelegate = Navigation()
        s.progressDelegate = Progress()
        s.contentDelegate = Content()
        s.permissionDelegate = Permissions()
        s.promptDelegate = Prompts(this)
        s.open(runtime)
        geckoView.setSession(s)
        session = s
        helper?.let { attachBridge(s, it) }
    }

    /** Installs uBlock Origin and the TV Time helper (both ship inside the APK), then opens Home. */
    private fun installExtensionsThenLoad() {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn("resource://android/assets/ublock/", UBLOCK_ID).accept({ ext ->
            ublock = ext
            if (ext != null && !adblockOn) controller.disable(ext, WebExtensionController.EnableSource.USER)
            Log.i(TAG, "uBlock Origin ready: ${ext?.metaData?.version}")
            installHelper()
        }, { e ->
            Log.e(TAG, "uBlock Origin failed to install", e)
            osd("Ad blocker could not start", 4000)
            installHelper()
        })
    }

    private fun installHelper() {
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/tvtime/", HELPER_ID).accept({ ext ->
            helper = ext
            if (ext != null) {
                startPage = ext.metaData.baseUrl.trimEnd('/') + "/start/index.html"
                session?.let { attachBridge(it, ext) }
            }
            session?.loadUri(homeUrl())
        }, { e ->
            Log.e(TAG, "TV Time helper failed to install", e)
            session?.loadUri(homeUrl())
        })
    }

    /** The helper's content script in every frame connects here; the remote's media keys go out through it. */
    private fun attachBridge(s: GeckoSession, ext: WebExtension) {
        s.webExtensionController.setMessageDelegate(ext, object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                ports.add(port)
                port.setDelegate(object : WebExtension.PortDelegate {
                    override fun onDisconnect(port: WebExtension.Port) {
                        ports.remove(port)
                    }
                })
            }
        }, "tvtime")
    }

    // ---------- home / sites ----------

    private fun homeUrl(): String = prefs.getString(PREF_HOME, null) ?: startPage ?: "about:blank"

    private fun refreshHomeSite() {
        allowedSites.addAll(DEFAULT_SITES)
        prefs.getString(PREF_HOME, null)?.let { Uri.parse(it).host?.let { h -> allowedSites.add(SiteKey.of(h)) } }
    }

    private fun onStartPage(): Boolean {
        val sp = startPage ?: return false
        return !inFullscreen && currentUrl.startsWith(sp.substringBeforeLast('/'))
    }

    // ---------- GeckoView delegates ----------

    private inner class Navigation : GeckoSession.NavigationDelegate {
        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            this@MainActivity.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            this@MainActivity.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val deny = blockNavigation(Uri.parse(request.uri), request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW)
            return GeckoResult.fromValue(if (deny) AllowOrDeny.DENY else AllowOrDeny.ALLOW)
        }

        // Pop-ups and new windows are never opened.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            Log.i(TAG, "Blocked new window: $uri")
            return null
        }
    }

    private inner class Progress : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            currentUrl = url
            menuUrl.text = url
            ports.clear()
            // The start page is driven with arrow keys; everywhere else the pointer is used.
            if (onStartPage()) cursor.hideCursor()
            else if (!playerMode && menu.visibility != View.VISIBLE && url != "about:blank") cursor.showCursor()
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            progress.visibility = View.GONE
        }

        override fun onProgressChange(session: GeckoSession, progressValue: Int) {
            progress.visibility = if (progressValue in 1..99) View.VISIBLE else View.GONE
            progress.progress = progressValue
        }
    }

    private inner class Content : GeckoSession.ContentDelegate {
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            inFullscreen = fullScreen
            setPlayerMode(fullScreen)
            goImmersive()
        }

        override fun onCrash(session: GeckoSession) = restartSession()
        override fun onKill(session: GeckoSession) = restartSession()
    }

    private fun restartSession() {
        // The page's content process died (usually out of memory on small TV boxes): start over.
        handler.post {
            geckoView.releaseSession()
            session?.close()
            ports.clear()
            openSession()
            session?.loadUri(homeUrl())
        }
    }

    private inner class Permissions : GeckoSession.PermissionDelegate {
        // Let videos (and the intro's sound) start without a tap; refuse everything else.
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int>? {
            val allow = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ||
                perm.permission == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
            return GeckoResult.fromValue(if (allow) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
        }
    }

    /** Returns true when a navigation must NOT happen. */
    private fun blockNavigation(uri: Uri, newWindow: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (newWindow) return true
        if (scheme in setOf("about", "data", "blob", "javascript", "moz-extension", "resource")) return false
        if (onStartPage() && (scheme == "http" || scheme == "https")) {
            // Tiles on the start page are sites you chose yourself: always allowed.
            uri.host?.let { allowedSites.add(SiteKey.of(it)) }
            return false
        }
        if (scheme != "http" && scheme != "https") {
            // intent://, market://, tg:// ... on these sites they are always ads trying to open other apps
            runOnUiThread { osd("Blocked link to another app") }
            return true
        }
        if (strictOn && SiteKey.of(uri.host) !in allowedSites) {
            runOnUiThread { osd("Blocked redirect to ${uri.host}") }
            return true
        }
        return false
    }

    // ---------- self-update ----------

    private enum class UpdateState { NONE, DOWNLOADING, FAILED, READY, NEED_PERMISSION, INSTALLING }

    private val updater by lazy { Updater(this) }
    private var updateState = UpdateState.NONE
    private var updateRelease: Updater.Release? = null
    private var updateFile: File? = null
    private var installerShown = false

    private fun checkForUpdate() {
        File(cacheDir, "update.apk").delete()
        Thread {
            val release = updater.check()
            if (release != null) runOnUiThread { showUpdate(release) }
        }.apply { isDaemon = true }.start()
    }

    private fun showUpdate(release: Updater.Release) {
        if (isFinishing || updateState != UpdateState.NONE) return
        updateRelease = release
        // Stop whatever is playing (the intro) and put the update screen on top of everything.
        if (inFullscreen) session?.exitFullScreen()
        menu.visibility = View.GONE
        cursor.hideCursor()
        session?.loadUri("about:blank")
        updateView.visibility = View.VISIBLE
        updateView.requestFocus()
        startDownload()
    }

    private fun startDownload() {
        val release = updateRelease ?: return
        updateState = UpdateState.DOWNLOADING
        updateText.text = "A new version of TV Time (${release.versionName}) is ready.\nDownloading..."
        updateHint.text = "0%"
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0
        Thread {
            try {
                val file = updater.download(release) { percent ->
                    runOnUiThread {
                        if (percent < 0) {
                            updateProgress.isIndeterminate = true
                            updateHint.text = "Downloading..."
                        } else {
                            updateProgress.progress = percent
                            updateHint.text = "$percent%"
                        }
                    }
                }
                runOnUiThread {
                    updateFile = file
                    updateState = UpdateState.READY
                    updateProgress.isIndeterminate = false
                    updateProgress.progress = 100
                    updateText.text = "Download complete."
                    updateHint.text = "Press OK to install. TV Time will close - then open it again."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateState = UpdateState.FAILED
                    updateProgress.isIndeterminate = false
                    updateText.text = "The download failed. Check the internet connection."
                    updateHint.text = "OK = try again    BACK = skip for now"
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** OK on the update screen. */
    private fun onUpdateOk() {
        when (updateState) {
            UpdateState.FAILED -> startDownload()
            UpdateState.READY, UpdateState.NEED_PERMISSION -> {
                if (updater.needsInstallPermission()) {
                    updateState = UpdateState.NEED_PERMISSION
                    updateText.text = "Allow TV Time to install updates."
                    updateHint.text = "Turn on \"Allow from this source\", then press BACK to return here."
                    try {
                        startActivity(updater.installPermissionIntent())
                    } catch (e: Exception) {
                        updateHint.text = "Open Settings > Apps > TV Time and allow installing apps, then press OK."
                    }
                    return
                }
                val file = updateFile ?: return startDownload()
                updateState = UpdateState.INSTALLING
                installerShown = false
                updateText.text = "Installing..."
                updateHint.text = "Confirm the update on the next screen."
                Thread {
                    try {
                        updater.install(file)
                    } catch (e: Exception) {
                        runOnUiThread {
                            updateState = UpdateState.READY
                            updateText.text = "Could not start the installer."
                            updateHint.text = "Press OK to try again."
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
            else -> {}
        }
    }

    /** Coming back from the "allow installs" settings or a cancelled installer. */
    private fun onUpdateResume() {
        when {
            updateState == UpdateState.NEED_PERMISSION && !updater.needsInstallPermission() -> {
                updateState = UpdateState.READY
                onUpdateOk()
            }
            updateState == UpdateState.NEED_PERMISSION -> {
                updateHint.text = "Installing updates is not allowed yet. Press OK to open the setting again."
            }
            updateState == UpdateState.INSTALLING && installerShown -> {
                updateState = UpdateState.READY
                updateText.text = "The update was not installed."
                updateHint.text = "Press OK to try again."
            }
        }
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
        val dx = scrollX.toDouble()
        val dy = scrollY.toDouble()
        scrollX = 0f
        scrollY = 0f
        if (!inFullscreen) {
            geckoView.panZoomController.scrollBy(ScreenLength.fromPixels(dx), ScreenLength.fromPixels(dy))
        }
    }

    /** Synthesises a finger tap at the pointer, which works inside any iframe / video player. */
    private fun tap(x: Float, y: Float) {
        if (!geckoView.hasFocus()) geckoView.requestFocus()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(down)
        down.recycle()
        handler.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            geckoView.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    /** Mouse-hover so menus that open on hover work too. */
    private fun hover(x: Float, y: Float) {
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
        geckoView.dispatchGenericMotionEvent(ev)
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
        } else if (!onStartPage()) {
            cursor.showCursor()
        }
    }

    /** Sends a media command to the video in every frame of the page (see web/gecko/bridge.js). */
    private fun media(cmd: String) {
        val msg = JSONObject().put("cmd", cmd)
        ports.toList().forEach { port ->
            try {
                port.postMessage(msg)
            } catch (e: Exception) {
                ports.remove(port)
            }
        }
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
            } else if (event.isLongPress && !longPressFired && longPressKey == event.keyCode) {
                // Android itself says this is a long press: open the menu now.
                handler.removeCallbacks(longPressRunnable)
                longPressRunnable.run()
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                if (longPressKey == event.keyCode && !longPressFired) short()
                longPressKey = -1
            }
        }
        return true
    }

    private fun sendKeyToPage(code: Int) {
        if (!geckoView.hasFocus()) geckoView.requestFocus()
        geckoView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        geckoView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // The update screen takes every key: OK continues, BACK leaves (or skips a failed download).
        if (updateView.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (isOk(code)) {
                    onUpdateOk()
                } else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (updateState == UpdateState.FAILED) {
                        updateView.visibility = View.GONE
                        updateState = UpdateState.NONE
                        session?.loadUri(homeUrl())
                    } else {
                        finish()
                    }
                }
            }
            return true
        }

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

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
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
                if (down) geckoView.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromVisualViewportHeight(-0.85))
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) geckoView.panZoomController.scrollBy(ScreenLength.zero(), ScreenLength.fromVisualViewportHeight(0.85))
                return true
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
                    if (!geckoView.hasFocus()) geckoView.requestFocus()
                    return super.dispatchKeyEvent(event)
                }
            }
            if (isOk(code)) return longPressable(event) { sendKeyToPage(KeyEvent.KEYCODE_ENTER) }
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
            inFullscreen -> session?.exitFullScreen()
            playerMode -> setPlayerMode(false)
            canGoBack -> session?.goBack()
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
            Item({ "Home" }) { hideMenu(); session?.loadUri(homeUrl()) },
            Item({ "Start page (Streamex, YouTube, your sites)" }) { hideMenu(); startPage?.let { session?.loadUri(it) } },
            Item({ "Back" }) { hideMenu(); if (canGoBack) session?.goBack() },
            Item({ "Forward" }) { hideMenu(); if (canGoForward) session?.goForward() },
            Item({ "Reload" }) { hideMenu(); session?.reload() },
            Item({ "Go to address / search..." }) { askAddress() },
            Item({ "Player controls mode" }) { hideMenu(); setPlayerMode(true) },
            Item({ "Ad blocker (uBlock Origin): " + if (adblockOn) "ON" else "OFF" }) {
                adblockOn = !adblockOn
                prefs.edit().putBoolean(PREF_ADBLOCK, adblockOn).apply()
                ublock?.let { ext ->
                    val c = runtime.webExtensionController
                    if (adblockOn) c.enable(ext, WebExtensionController.EnableSource.USER)
                    else c.disable(ext, WebExtensionController.EnableSource.USER)
                }
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
                session?.settings?.apply {
                    userAgentMode = if (on) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    viewportMode = if (on) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                }
                hideMenu()
                session?.reload()
            },
            Item({ "Make this page my Home" }) {
                currentUrl.takeIf { it.startsWith("http") }?.let {
                    prefs.edit().putString(PREF_HOME, it).apply()
                    refreshHomeSite()
                    osd("Home set")
                }
                hideMenu()
            },
            Item({ "Clear cookies & cache" }) {
                runtime.storageController.clearData(
                    StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.ALL_CACHES or
                        StorageController.ClearFlags.SITE_DATA,
                )
                hideMenu()
                session?.reload()
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
        menuUrl.text = currentUrl
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
        geckoView.requestFocus()
        if (!playerMode && !onStartPage()) cursor.showCursor()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            setText(currentUrl.takeIf { it.startsWith("http") } ?: "")
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
        allowedSites.add(SiteKey.of(Uri.parse(url).host))
        session?.loadUri(url)
    }

    companion object {
        private const val TAG = "TvTime"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val HELPER_ID = "tvtime@tvtime.app"
        private val DEFAULT_SITES = listOf("streamex", "youtube", "youtu", "youtube-nocookie", "google")
        private const val PREF_HOME = "home_url"
        private const val PREF_ADBLOCK = "adblock"
        private const val PREF_STRICT = "strict"
        private const val PREF_DESKTOP = "desktop"
    }
}
