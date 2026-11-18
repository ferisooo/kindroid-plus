package com.ferisooo.kindroidplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

/**
 * The selfie slideshow: the Kin's selfies take turns as the chat background.
 * assets/slides.js does it on the page; this side keeps the settings, gives the page
 * a bridge to read them, and shows a small 🖼 button on chat pages that opens them.
 *
 * When Kindroid won't share the selfie list, the page asks for the gallery to be looked
 * through: a hidden copy of the chat page opens on the Kin's photo gallery behind the real
 * one, scrolls through it and keeps the selfies it shows ([gatherFrom]), at most every
 * [GATHER_EVERY_MS] per Kin.
 */
class Slides(private val activity: Activity, private val root: FrameLayout) {

    private val prefs = activity.getSharedPreferences("slides", Context.MODE_PRIVATE)

    /** Made up each run, so no page can count on it. */
    private val bridgeName = "kp" + UUID.randomUUID().toString().replace("-", "").take(10)

    /** What the page last said: how many selfies it found, or what went wrong (JSON). */
    @Volatile private var lastReport = ""

    private val source: String by lazy { activity.assets.open("slides.js").bufferedReader().use { it.readText() } }

    private fun script(mode: String) = "(function (KP_BRIDGE, KP_MODE) {\n$source\n})('$bridgeName', '$mode');"

    /** The hidden gallery page while it's looking (null otherwise), the Kin, and how many so far. */
    private var gatherer: WebView? = null
    private var gatheringKin: String? = null
    @Volatile private var gatheredSoFar = 0

    private val on get() = prefs.getBoolean(ON, true)
    private val order get() = prefs.getString(ORDER, null)?.takeIf { it in ORDERS.map { o -> o.first } } ?: "random"
    private val seconds get() = prefs.getInt(SECONDS, 10).takeIf { it in SECONDS_CHOICES } ?: 10
    private val bubbles get() = prefs.getString(BUBBLES, null)?.takeIf { it in BUBBLE_CHOICES.map { b -> b.first } } ?: "glass"

    private inner class Bridge {
        @JavascriptInterface
        fun settings(): String =
            JSONObject().put("on", on).put("order", order).put("seconds", seconds).put("bubbles", bubbles).toString()

        /** A Kindroid sheet (gallery, scene, settings) opened or closed on the page. */
        @JavascriptInterface
        fun sheet(open: Boolean) {
            activity.runOnUiThread {
                sheetOpen = open
                pageChanged(lastUrl)
            }
        }

        /** The ✨ Ideas button on Kindroid's scene box: what the page knows about the chat. */
        @JavascriptInterface
        fun ideas(json: String) {
            val context = runCatching { JSONObject(json) }.getOrNull()
            activity.runOnUiThread { sceneIdeas.open(context) }
        }

        @JavascriptInterface
        fun report(json: String) {
            val said = runCatching { JSONObject(json) }.getOrNull() ?: return
            val kin = said.optString("kin")
            if (said.optString("mode") == "gather") {
                gatheredSoFar = said.optInt("count")
                if (said.optBoolean("done")) activity.runOnUiThread { stopGathering(finished = kin) }
                return
            }
            lastReport = json.take(1000)
            if (said.optBoolean("needGallery") && kin.isNotEmpty()) activity.runOnUiThread { gatherFrom(kin) }
        }
    }

    /** The page you see (the last one attached), for putting a scene idea in its box. */
    private var page: WebView? = null

    fun attach(web: WebView) {
        page = web
        web.addJavascriptInterface(Bridge(), bridgeName)
    }

    /** ✨ Scene ideas: the owner's own AI writes a suggestion for Kindroid's scene box. */
    val sceneIdeas = SceneIdeas(activity) { text, done ->
        val web = page
        if (web == null) done(false)
        else web.evaluateJavascript("window.__kpSetScene ? window.__kpSetScene(${JSONObject.quote(text)}) : false") { done(it == "true") }
    }

    /** Opens scene ideas from the menu, with what the page knows about the chat (if it's a chat). */
    private fun openIdeasFromMenu() {
        val web = page
        if (web == null) {
            sceneIdeas.open(null)
            return
        }
        web.evaluateJavascript("window.__kpSceneContext ? window.__kpSceneContext() : null") { result ->
            // The page's answer comes as JSON: a quoted string holding the context's JSON.
            val context = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrNull()
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            sceneIdeas.open(context)
        }
    }

    /** Starts the slideshow on a Kindroid page (it runs once per page and watches for chats itself). */
    fun inject(web: WebView) = web.evaluateJavascript(script("show"), null)

    /**
     * Opens the Kin's photo gallery in a hidden page behind the real one (so it's drawn and
     * loads like it's on screen) and lets slides.js scroll through it. Not more often than
     * every [GATHER_EVERY_MS] for a Kin, one at a time, only while the app is on screen.
     */
    private fun gatherFrom(kin: String) {
        if (gatherer != null || activity.isFinishing) return
        if (System.currentTimeMillis() - prefs.getLong(GATHERED_AT + kin, 0) < GATHER_EVERY_MS) return
        if (!KIN_ID.matches(kin)) return
        val page = WebView(activity)
        page.setBackgroundColor(Color.BLACK)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // No sound from a page nobody sees.
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(page, true)
        page.addJavascriptInterface(Bridge(), bridgeName)
        page.webViewClient = object : android.webkit.WebViewClient() {
            // It never leaves Kindroid, and never opens other apps.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                !(request.url.scheme == "https" && MainActivity.isKindroid(request.url.host))

            override fun onPageCommitVisible(view: WebView, url: String) = start(view, url)

            override fun onPageFinished(view: WebView, url: String) = start(view, url)

            private fun start(view: WebView, url: String) {
                if (MainActivity.isKindroid(Uri.parse(url).host)) view.evaluateJavascript(script("gather"), null)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                activity.runOnUiThread { stopGathering(finished = null) }
                return true
            }
        }
        gatherer = page
        gatheringKin = kin
        gatheredSoFar = 0
        // Behind everything: the real page covers it.
        root.addView(page, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        page.loadUrl("https://kindroid.ai/v2/chat/${Uri.encode(kin)}?gallery=1&media=photo")
        root.postDelayed({ if (gatherer === page) stopGathering(finished = null) }, GATHER_LONGEST_MS)
    }

    /** Closes the hidden gallery page. [finished]: the Kin whose gallery was looked through to the end. */
    fun stopGathering(finished: String?) {
        val page = gatherer ?: return
        if (finished != null && finished == gatheringKin) {
            prefs.edit().putLong(GATHERED_AT + finished, System.currentTimeMillis()).apply()
        }
        gatherer = null
        gatheringKin = null
        root.removeView(page)
        page.destroy()
    }

    /** The round 🖼 button at the right edge. Drag it up or down; tap it for the settings. */
    val button: TextView = TextView(activity).apply {
        text = "🖼"
        textSize = 20f
        gravity = Gravity.CENTER
        alpha = RESTING_ALPHA
        contentDescription = "Selfie slideshow"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(0xCC, 0x1E, 0x1A, 0x22))
            setStroke(dp(1), Color.argb(0x99, 0xFF, 0x6F, 0xA5))
        }
        visibility = View.GONE
        setOnClickListener { openSettings() }
        setOnTouchListener(Dragger())
    }

    fun buttonParams() = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { rightMargin = dp(6) }

    init {
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    /** Whether one of Kindroid's sheets is open on the page (then the 🖼 button keeps out of its way). */
    var sheetOpen = false
        private set
    private var lastUrl: String? = null

    /** The button only shows in a Kin's chat (not group chats or other pages), and not over a sheet. */
    fun pageChanged(url: String?) {
        lastUrl = url
        button.visibility = if (isKinChat(url) && !sheetOpen) View.VISIBLE else View.GONE
    }

    /** The phone's back button with a sheet open: closes the sheet instead of leaving the chat. */
    fun closeSheet(web: WebView) {
        web.evaluateJavascript("window.__kpCloseSheet ? window.__kpCloseSheet() : false") { done ->
            if (done != "true" && web.canGoBack()) web.goBack()
        }
    }

    private fun room(): Float = (root.height - root.paddingTop - root.paddingBottom - button.height).coerceAtLeast(0).toFloat()

    private fun place() {
        button.translationY = prefs.getFloat(BUTTON_AT, 0.2f).coerceIn(0f, 1f) * room()
    }

    private inner class Dragger : View.OnTouchListener {
        private val slop = ViewConfiguration.get(activity).scaledTouchSlop
        private var downY = 0f
        private var startY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = v.translationY
                    dragging = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = e.rawY - downY
                    if (!dragging && abs(moved) > slop) dragging = true
                    if (dragging) v.translationY = (startY + moved).coerceIn(0f, room())
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = RESTING_ALPHA
                    if (dragging) {
                        val r = room()
                        prefs.edit().putFloat(BUTTON_AT, if (r > 0) v.translationY / r else 0.2f).apply()
                    } else {
                        v.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = RESTING_ALPHA
            }
            return true
        }
    }

    private fun openSettings() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), dp(4))
        }
        box.addView(Switch(activity).apply {
            text = "Selfies as the chat background"
            textSize = 16f
            isChecked = on
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(ON, checked).apply() }
        })
        // Three drop-downs side by side: order, speed, and how see-through the message boxes are
        // (Kindroid's own are always solid).
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            addView(dropdown("Order", ORDERS, order) { prefs.edit().putString(ORDER, it).apply() }, third())
            addView(dropdown("Speed", SECONDS_CHOICES.map { Triple(it.toString(), secondsLabel(it), shortSeconds(it)) }, seconds.toString()) {
                prefs.edit().putInt(SECONDS, it.toInt()).apply()
            }, third())
            addView(dropdown("Boxes", BUBBLE_CHOICES, bubbles) { prefs.edit().putString(BUBBLES, it).apply() }, third())
        })
        status()?.let { line ->
            box.addView(TextView(activity).apply {
                text = line
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(14), 0, 0)
            })
        }
        box.addView(heading("✨ Scene ideas"))
        var dialog: AlertDialog? = null
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Write a scene ✨") { dialog?.dismiss(); openIdeasFromMenu() }, half())
            addView(pill(sceneIdeas.provider?.let { "AI: ${it.label}" } ?: "Set up your AI") { dialog?.dismiss(); sceneIdeas.setUp() }, half())
        })
        dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Kindroid+ 💕")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun third() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }

    private fun half() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }

    /** A rounded button. */
    private fun pill(words: String, onTap: (View) -> Unit) = TextView(activity).apply {
        text = words
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(8), dp(11), dp(8), dp(11))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            setStroke(dp(1), Color.argb(0x66, 0xFF, 0x6F, 0xA5))
        }
        setOnClickListener { onTap(it) }
    }

    /**
     * A small title with a drop-down button under it. [options]: (key, words in the list,
     * short words on the button). Picking one saves it at once.
     */
    private fun dropdown(title: String, options: List<Triple<String, String, String>>, picked: String, onPick: (String) -> Unit): View {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        column.addView(heading(title).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
        val shown = options.firstOrNull { it.first == picked } ?: options.first()
        column.addView(pill("${shown.third} ▾") { v ->
            android.widget.PopupMenu(activity, v).apply {
                options.forEachIndexed { i, o -> menu.add(0, i, i, o.second) }
                setOnMenuItemClickListener { item ->
                    val o = options[item.itemId]
                    onPick(o.first)
                    (v as TextView).text = "${o.third} ▾"
                    true
                }
                show()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return column
    }

    /** What the page found last time, for the bottom of the pop-up. */
    private fun status(): String? {
        val json = runCatching { JSONObject(lastReport) }.getOrNull()
        val lines = mutableListOf<String>()
        if (gatherer != null) lines += "🔎 Looking through the selfie gallery… $gatheredSoFar so far"
        if (json != null) {
            val count = json.optInt("count")
            val selfies = if (count == 1) "1 selfie" else "$count selfies"
            val fromGallery = json.optString("from") == "gallery"
            when {
                count > 0 -> lines += "Found $selfies" + if (fromGallery) " in the gallery" else ""
                json.optString("waiting").isNotEmpty() -> lines += json.optString("waiting") + "…"
                json.optString("problem").isNotEmpty() -> lines += "⚠️ " + json.optString("problem")
                fromGallery && gatherer == null -> lines += "⚠️ No selfies found yet"
            }
            json.optString("listProblem").takeIf { it.isNotEmpty() }?.let { lines += "($it)" }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun heading(words: String) = TextView(activity).apply {
        text = words
        textSize = 14f
        setTextColor(Color.rgb(0xFF, 0x6F, 0xA5))
        setPadding(0, dp(16), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val ON = "on"
        private const val ORDER = "order"
        private const val SECONDS = "seconds"
        private const val BUBBLES = "bubbles"
        private const val BUTTON_AT = "button_at"
        private const val GATHERED_AT = "gathered_at:"
        private const val RESTING_ALPHA = 0.75f
        private const val GATHER_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val GATHER_LONGEST_MS = 3 * 60 * 1000L
        private val KIN_ID = Regex("[A-Za-z0-9_-]{1,128}")

        val ORDERS = listOf(
            Triple("random", "Random", "Random"),
            Triple("newest", "Newest first", "Newest"),
            Triple("oldest", "Oldest first", "Oldest"),
        )
        val SECONDS_CHOICES = listOf(1, 2, 3, 5, 10, 30, 60, 300)
        val BUBBLE_CHOICES = listOf(
            Triple("solid", "Solid (Kindroid's own)", "Solid"),
            Triple("glass", "See-through", "See-through"),
            Triple("clear", "Very see-through", "Very clear"),
            Triple("none", "Invisible (only the words)", "Invisible"),
        )

        fun shortSeconds(s: Int) = if (s % 60 == 0) "${s / 60} min" else "$s sec"

        fun secondsLabel(s: Int) = when {
            s == 1 -> "1 second"
            s % 60 == 0 && s / 60 == 1 -> "1 minute"
            s % 60 == 0 -> "${s / 60} minutes"
            else -> "$s seconds"
        }

        /** Kindroid's chat with one Kin: kindroid.ai/v2/chat/<kin id>. */
        fun isKinChat(url: String?): Boolean {
            val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
            if (!MainActivity.isKindroid(uri.host)) return false
            val parts = uri.pathSegments
            return parts.size == 3 && parts[0] == "v2" && parts[1] == "chat" && parts[2] != "group"
        }
    }
}
