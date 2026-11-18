package com.ferisooo.kindroidplus

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Kindroid in its own app. You log in once (email, or Apple) and stay logged in:
 * the web engine keeps Kindroid's cookies and saved data between runs.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var loading: ProgressBar
    private lateinit var offline: TextView

    /** Covers the empty page while Kindroid starts up, so it doesn't look stuck. */
    private lateinit var starting: View

    /** The selfie slideshow behind the chat, and its 🖼 button. */
    private lateinit var slides: Slides

    /** The app lock (PIN, pattern or password), if one is set. */
    private lateinit var lock: Lock

    /** The owner's own pictures for the slideshow. */
    private lateinit var myPictures: MyPictures

    /** True while the app itself sent you to another screen (a picker): coming back doesn't lock. */
    private var awayForPicker = false

    /** When the app went out of sight (0: hasn't). */
    private var leftAt = 0L

    /** A sign-in window a page opened ("Sign in with Apple" and the like), shown over Kindroid. */
    private var popup: WebView? = null

    /** Where to send the files picked for an upload on the page. */
    private var filesWanted: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        // Nobody with a cable can look inside the logged-in page (phones for app makers still allow it).
        WebView.setWebContentsDebuggingEnabled(false)
        myPictures = MyPictures(this) { intent, code -> launchForResult(intent, code) }
        lock = Lock(this, root) { startOver() }
        slides = Slides(this, root, myPictures, lock)

        web = newPage()
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(slides.button, slides.buttonParams())

        // A thin line at the top while a page loads.
        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.GONE
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP))

        // Shown when Kindroid can't be reached. A tap tries again.
        offline = TextView(this).apply {
            text = "Can't reach Kindroid.\nCheck your internet, then tap to try again."
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(BACKGROUND)
            setPadding(dp(32), 0, dp(32), 0)
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                web.reload()
            }
        }
        root.addView(offline, FrameLayout.LayoutParams(MATCH, MATCH))

        // A pink spinner in the middle while Kindroid starts up.
        starting = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(ProgressBar(this@MainActivity).apply {
                indeterminateTintList = ColorStateList.valueOf(ACCENT)
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        root.addView(starting, FrameLayout.LayoutParams(MATCH, MATCH))
        // Never longer than this, even if the page is slow to say it's done.
        root.postDelayed({ hideStarting() }, START_COVER_MS)

        setContentView(root)
        keepClearOfSystemBars()
        // Locked from the start (the page loads behind the lock meanwhile).
        lock.lockNow()
        // Back where you were last time (the chat you had open), not the start page.
        web.loadUrl(lastPage() ?: HOME)
    }

    private fun newPage(): WebView = WebView(this).also {
        setUp(it)
        // Keeps the page drawn while the app is away, so coming back shows it at once.
        it.settings.offscreenPreRaster = true
        it.webViewClient = MainClient()
        it.webChromeClient = MainChrome()
        slides.attach(it)
    }

    private fun hideStarting() {
        if (starting.visibility != View.VISIBLE) return
        starting.animate().alpha(0f).setDuration(200).withEndAction { starting.visibility = View.GONE }.start()
    }

    private fun prefs() = getSharedPreferences("app", MODE_PRIVATE)

    /** The Kindroid page open when you last left the app. */
    private fun lastPage(): String? =
        prefs().getString(LAST_PAGE, null)?.takeIf { isKindroid(Uri.parse(it).host) }

    private fun rememberPage() {
        val url = web.url ?: return
        if (isKindroid(Uri.parse(url).host)) prefs().edit().putString(LAST_PAGE, url).apply()
    }

    /**
     * Android stopped the page's engine (it does when the phone runs low on memory).
     * Without this the whole app would close; instead Kindroid opens again where it was.
     */
    private fun restartPage(gone: WebView) {
        if (gone != web) {
            if (gone == popup) closeSoon()
            return
        }
        val url = gone.url?.takeIf { isKindroid(Uri.parse(it).host) } ?: lastPage() ?: HOME
        // The hidden gallery page sits behind the real one; it goes too, so the new page is in front.
        slides.stopGathering(finished = null)
        root.removeView(gone)
        gone.destroy()
        web = newPage()
        root.addView(web, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        web.loadUrl(url)
    }

    /** Settings both Kindroid and its sign-in windows use. */
    private fun setUp(view: WebView) {
        view.setBackgroundColor(BACKGROUND)
        view.settings.apply {
            javaScriptEnabled = true
            // Kindroid keeps your login and settings in the page's own storage.
            domStorageEnabled = true
            // Voice messages can play without an extra tap.
            mediaPlaybackRequiresUserGesture = false
            // Sign-in buttons open their own window, which hands the login back to Kindroid.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // The login can come from a sign-in site other than Kindroid's own.
            setAcceptThirdPartyCookies(view, true)
        }
    }

    /**
     * The phone draws the app under its status bar, camera cutout and navigation bar
     * (Android 15 and up always does), so the page is padded to stay clear of them,
     * and of the keyboard, so the chat box is never hidden behind it.
     */
    private fun keepClearOfSystemBars() {
        if (Build.VERSION.SDK_INT < 30) return // Older phones fit the app between the bars themselves.
        @Suppress("DEPRECATION") // Android 15+ does this anyway; 11 to 14 still need it.
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private inner class MainClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            leavesApp(request, closeAfter = null)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) offline.visibility = View.VISIBLE
        }

        // As soon as the page shows, and again when it's done loading (the script only starts once a page).
        override fun onPageCommitVisible(view: WebView, url: String) = tidy(view, url)

        override fun onPageFinished(view: WebView, url: String) {
            tidy(view, url)
            hideStarting()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            restartPage(view)
            return true
        }

        // My pictures for the slideshow: the app answers /__kindroidplus/mine/… itself.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.scheme != "https" || !isKindroid(url.host)) return null
            return myPictures.serve(url.path ?: "")
        }

        private fun tidy(view: WebView, url: String) {
            if (!isKindroid(Uri.parse(url).host)) return
            view.evaluateJavascript(TIDY_JS, null)
            slides.inject(view)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = slides.pageChanged(url)

        // Kindroid moves between pages without loading new ones; this hears those moves too.
        // The page is remembered right away too, so even if the app is closed while on screen
        // it opens there again.
        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            slides.pageChanged(url)
            if (view == web) rememberPage()
        }
    }

    private inner class MainChrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loading.progress = newProgress
            loading.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Nothing opens over the lock.
            if (lock.isShowing) return false
            closePopup()
            val window = WebView(this@MainActivity)
            setUp(window)
            window.setBackgroundColor(Color.WHITE)
            window.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    leavesApp(request, closeAfter = window)
            }
            window.webChromeClient = object : WebChromeClient() {
                // The sign-in finished and closed itself: back to Kindroid.
                override fun onCloseWindow(closing: WebView) = closeSoon()
            }
            root.addView(window, FrameLayout.LayoutParams(MATCH, MATCH))
            popup = window
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = window
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(closing: WebView) = closeSoon()

        // An upload button on the page (a picture for your Kin, say): the phone's file picker.
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            filesWanted?.onReceiveValue(null)
            filesWanted = callback
            return try {
                launchForResult(params.createIntent(), PICK_FILES)
                true
            } catch (e: ActivityNotFoundException) {
                filesWanted = null
                Toast.makeText(this@MainActivity, "No app on this phone can pick files", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /**
     * Kindroid and its sign-in and payment pages stay in the app. Links to other sites
     * open in the phone's browser, and mail, phone and app links in their own apps.
     * True when the page was sent out of the app (and [closeAfter], a pop-up, is closed).
     */
    private fun leavesApp(request: WebResourceRequest, closeAfter: WebView?): Boolean {
        val url = request.url
        val leave = when (url.scheme) {
            "http", "https" -> request.isForMainFrame && !staysInApp(url.host)
            "about", "data", "blob", "javascript" -> false
            else -> true
        }
        if (!leave) return false
        openOutside(url)
        if (closeAfter != null && closeAfter == popup) closeSoon()
        return true
    }

    private fun openOutside(url: Uri) {
        val intent = if (url.scheme == "intent") {
            runCatching { Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()?.apply {
                // Only apps that say they open links, never one of this app's own screens.
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else {
            Intent(Intent.ACTION_VIEW, url)
        }
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open that", Toast.LENGTH_SHORT).show()
        }
    }

    /** Closes the pop-up once the call it's in is over (it can't be torn down from inside itself). */
    private fun closeSoon() {
        root.post { closePopup() }
    }

    private fun closePopup() {
        val window = popup ?: return
        popup = null
        root.removeView(window)
        window.destroy()
    }

    // Still called: the manifest turns off Android 16's new back handling.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (lock.onBack()) return
        val window = popup
        when {
            window != null && window.canGoBack() -> window.goBack()
            window != null -> closePopup()
            // A Kindroid sheet (the gallery, the scene…) or the Kin's menu adds no page to go back
            // from: the page closes it. Otherwise back a page, or hide the app (instead of closing
            // it, so it opens again at once, still on the same page).
            else -> slides.back(web) { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == MyPictures.PICK) {
            myPictures.picked(resultCode, data)
            return
        }
        if (requestCode != PICK_FILES) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callback = filesWanted ?: return
        filesWanted = null
        // Several files come in clipData; one comes as data.
        val clip = data?.clipData
        val picked = if (resultCode == RESULT_OK && clip != null) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        callback.onReceiveValue(picked)
    }

    /** Opens another app's screen for an answer (a picker); coming back from it doesn't lock. */
    @Suppress("DEPRECATION")
    private fun launchForResult(intent: Intent, code: Int) {
        startActivityForResult(intent, code)
        awayForPicker = true
    }

    override fun onStart() {
        super.onStart()
        // Back after being away: locks if it was away long enough (not after the app's own pickers).
        val away = SystemClock.elapsedRealtime() - leftAt
        if (leftAt > 0 && !awayForPicker && away >= lock.graceMs) lock.lockNow()
        awayForPicker = false
    }

    override fun onStop() {
        leftAt = SystemClock.elapsedRealtime()
        // Locked straight away (so the chat never shows when you come back), unless it waits a while.
        if (!awayForPicker && lock.graceMs == 0L) lock.lockNow()
        super.onStop()
    }

    /**
     * "Forgot?" on the lock: Kindroid+ forgets everything on this phone (the Kindroid login, the
     * lock, AI keys, my pictures, settings) and opens fresh. The Kindroid account is untouched.
     */
    private fun startOver() {
        web.evaluateJavascript(
            "try{localStorage.clear();sessionStorage.clear();indexedDB.databases&&indexedDB.databases().then(function(l){" +
                "l.forEach(function(d){indexedDB.deleteDatabase(d.name)})})}catch(e){}", null,
        )
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        web.clearCache(true)
        web.clearHistory()
        listOf("app", "slides", "ai", "lock").forEach { getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
        java.io.File(filesDir, "mine").deleteRecursively()
        Toast.makeText(this, "Starting over… log in to Kindroid again 💕", Toast.LENGTH_LONG).show()
        root.postDelayed({ recreate() }, 800)
    }

    /** A pop-up of the app's own (settings, AI, lock…) takes the focus: tell the page while it's open. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        slides.hold(!hasFocus)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        // Saves the login cookies to storage now, so they're there if the phone closes the app.
        CookieManager.getInstance().flush()
        rememberPage()
        // Looking through the gallery stops when you leave; it goes again another time.
        slides.stopGathering(finished = null)
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        slides.stopGathering(finished = null)
        closePopup()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val HOME = "https://kindroid.ai/"

        /** Sites that open inside the app: Kindroid itself, and the sign-in and payment pages it uses. */
        private val IN_APP_SITES = listOf(
            "kindroid.ai", "kindroid.com",
            // Google's login service that Kindroid's sign-in runs on.
            "firebaseapp.com", "web.app", "googleapis.com", "google.com", "gstatic.com",
            "apple.com", "stripe.com", "stripe.network",
        )

        fun staysInApp(host: String?): Boolean = onSite(host, IN_APP_SITES)

        fun isKindroid(host: String?): Boolean = onSite(host, listOf("kindroid.ai", "kindroid.com"))

        private fun onSite(host: String?, sites: List<String>): Boolean {
            val h = host?.lowercase()?.trimEnd('.') ?: return false
            return sites.any { h == it || h.endsWith(".$it") }
        }

        /**
         * Hides Kindroid's "Open in your browser" box (it only says Google and Apple
         * sign-in may not work here; the email login does). It finds the box by its
         * heading, then hides the biggest piece around it that's still only the box
         * (never the email login under it). Kindroid changes screens without loading
         * a new page, so it keeps watching for the box to come back.
         */
        private val TIDY_JS = """
            (function () {
              if (window.__kindroidPlusTidy) return;
              window.__kindroidPlusTidy = true;
              var HEADING = 'open in your browser';
              function hideBox() {
                var root = document.body;
                if (!root) return;
                var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
                var node;
                while ((node = walker.nextNode())) {
                  if (node.nodeValue.trim().toLowerCase() !== HEADING) continue;
                  var box = null;
                  for (var el = node.parentElement; el && el !== root; el = el.parentElement) {
                    var text = (el.textContent || '').toLowerCase();
                    if (text.length > 400 || el.querySelector('input, textarea, select') ||
                        text.indexOf('welcome') >= 0 || text.indexOf('login') >= 0 || text.indexOf('terms of') >= 0) break;
                    box = el;
                  }
                  if (box) box.style.setProperty('display', 'none', 'important');
                }
              }
              hideBox();
              var waiting = false;
              new MutationObserver(function (changes) {
                if (waiting) return;
                for (var i = 0; i < changes.length; i++) {
                  var added = changes[i].addedNodes;
                  for (var j = 0; j < added.length; j++) {
                    if ((added[j].textContent || '').toLowerCase().indexOf(HEADING) >= 0) {
                      waiting = true;
                      setTimeout(function () { waiting = false; hideBox(); }, 0);
                      return;
                    }
                  }
                }
              }).observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()

        private const val PICK_FILES = 1
        private const val LAST_PAGE = "last_page"
        private const val START_COVER_MS = 8_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val BACKGROUND = Color.rgb(0x12, 0x10, 0x16)
        private val ACCENT = Color.rgb(0xFF, 0x6F, 0xA5)
    }
}
