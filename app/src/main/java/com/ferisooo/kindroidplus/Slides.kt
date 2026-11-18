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
class Slides(
    private val activity: Activity,
    private val root: FrameLayout,
    private val myPictures: MyPictures,
    private val lock: Lock,
) {

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
    private val showPhotos get() = prefs.getBoolean(PHOTOS, true)
    private val showVideos get() = prefs.getBoolean(VIDEOS, true)
    private val showMine get() = prefs.getBoolean(MINE, true)
    private val favoritesOnly get() = prefs.getBoolean(FAVORITES, false)

    private inner class Bridge {
        @JavascriptInterface
        fun settings(): String = JSONObject()
            .put("on", on).put("order", order).put("seconds", seconds).put("bubbles", bubbles)
            .put("photos", showPhotos).put("videos", showVideos).put("mine", showMine).put("mineGen", myPictures.gen)
            .put("favorites", favoritesOnly)
            .put("barsSlim", barsSlim).put("barsClear", barsClear).put("barsHide", barsHide)
            .toString()

        /** My pictures for the slideshow: [{url, at}]. */
        @JavascriptInterface
        fun myPictures(): String = myPictures.json()

        /** A Kindroid sheet (gallery, scene, settings) opened or closed on the page. */
        @JavascriptInterface
        fun sheet(open: Boolean) {
            activity.runOnUiThread {
                sheetOpen = open
                pageChanged(lastUrl)
            }
        }

        /** Something of Kindroid's covers the chat (a sheet, or the Kin's menu): the 🖼 button hides. */
        @JavascriptInterface
        fun cover(hide: Boolean) {
            activity.runOnUiThread {
                covered = hide
                pageChanged(lastUrl)
            }
        }

        /** A ✨ button on one of Kindroid's boxes: what the page knows about the box and the chat. */
        @JavascriptInterface
        fun ideas(json: String) {
            val context = runCatching { JSONObject(json) }.getOrNull()
            activity.runOnUiThread { if (!lock.isShowing) ideas.open(context) }
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

    /** ✨ Ideas: the owner's own AI writes for Kindroid's boxes; "Use it" fills the box of that kind. */
    val ideas = Ideas(activity) { text, kind, done ->
        val web = page
        if (web == null) done(false)
        else web.evaluateJavascript("window.__kpPut ? window.__kpPut(${JSONObject.quote(text)}, ${JSONObject.quote(kind)}) : false") { done(it == "true") }
    }

    /** Opens [kind] ideas from the menu, with what the page knows about the chat (if it's a chat). */
    private fun openIdeasFromMenu(kind: String) {
        val web = page
        if (web == null) {
            ideas.open(JSONObject().put("kind", kind))
            return
        }
        web.evaluateJavascript("window.__kpContext ? window.__kpContext(${JSONObject.quote(kind)}) : null") { result ->
            // The page's answer comes as JSON: a quoted string holding the context's JSON.
            val context = runCatching { org.json.JSONArray("[$result]").getString(0) }.getOrNull()
                ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject().put("kind", kind)
            ideas.open(context)
        }
    }

    /** Runs [js] on the page you see, and hands its answer (JSON) to [done]. */
    private fun onPage(js: String, done: (String?) -> Unit) {
        val web = page ?: return done(null)
        web.evaluateJavascript(js) { done(it) }
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
    private var covered = false
    private var lastUrl: String? = null

    /** The button only shows in a Kin's chat (not group chats or other pages), and not over a sheet. */
    fun pageChanged(url: String?) {
        lastUrl = url
        button.visibility = if (isKinChat(url) && !sheetOpen && !covered) View.VISIBLE else View.GONE
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

    /** Tiles of the pop-up's grid: their value line, worked out again after any change. */
    private val tileValues = mutableListOf<Pair<TextView, () -> String>>()

    private fun refreshTiles() = tileValues.forEach { (view, value) -> view.text = value() }

    /**
     * The "Kindroid+ 💕" pop-up: the slideshow switch, then a 3 by 3 grid of same-size tiles
     * (each a title and what's chosen; a tap opens its choices), then what was found.
     */
    private fun openSettings() {
        tileValues.clear()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }
        var dialog: AlertDialog? = null
        val close = { dialog?.dismiss() }
        box.addView(Switch(activity).apply {
            text = "Slideshow behind the chat"
            textSize = 16f
            isChecked = on
            setPadding(dp(4), 0, dp(4), dp(8))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(ON, checked).apply() }
        })
        val tiles = listOf(
            tile("Order", { ORDERS.first { it.first == order }.third }) { v ->
                menu(v, ORDERS.map { it.second to (it.first == order) }) { prefs.edit().putString(ORDER, ORDERS[it].first).apply() }
            },
            tile("Speed", { shortSeconds(seconds) }) { v ->
                menu(v, SECONDS_CHOICES.map { secondsLabel(it) to (it == seconds) }) { prefs.edit().putInt(SECONDS, SECONDS_CHOICES[it]).apply() }
            },
            tile("Boxes", { BUBBLE_CHOICES.first { it.first == bubbles }.third }) { v ->
                menu(v, BUBBLE_CHOICES.map { it.second to (it.first == bubbles) }) { prefs.edit().putString(BUBBLES, BUBBLE_CHOICES[it].first).apply() }
            },
            tile("Show", { showLabel() }) { chooseShow() },
            tile("⭐ Favorites", { if (favoritesOnly) "Only ⭐" else "All" }) { v ->
                menu(v, listOf(
                    "Show all" to !favoritesOnly,
                    "⭐ Favorites only" to favoritesOnly,
                    "⭐ Pick favorites…" to false,
                    "⭐ Star the one showing" to false,
                )) { which ->
                    when (which) {
                        0, 1 -> prefs.edit().putBoolean(FAVORITES, which == 1).apply()
                        2 -> {
                            close()
                            onPage("window.__kpOpenFavorites ? window.__kpOpenFavorites() : false") {
                                if (it != "true") toast("Open a chat with your Kin first")
                            }
                        }
                        3 -> onPage("window.__kpStarCurrent ? window.__kpStarCurrent() : 'none'") {
                            toast(when (it) {
                                "\"on\"" -> "⭐ Starred!"
                                "\"off\"" -> "Unstarred"
                                else -> "Nothing showing yet"
                            })
                        }
                    }
                }
            },
            tile("📱 Pictures", { myPictures.count().let { if (it == 1) "1 picture" else "$it pictures" } }) { v ->
                menu(v, listOf("Add pictures…" to false, "See all / take out…" to false)) {
                    close()
                    if (it == 0) myPictures.pick() else myPictures.manage()
                }
            },
            tile("✨ AI ideas", { ideas.provider?.label?.substringBefore(" (") ?: "Set up" }) { v ->
                menu(v, listOf("✨ Write a scene…" to false, "💬 What should I say?…" to false, "Your AI and key…" to false)) {
                    close()
                    when (it) {
                        0 -> openIdeasFromMenu("scene")
                        1 -> openIdeasFromMenu("reply")
                        else -> ideas.setUp()
                    }
                }
            },
            tile("Bars", { barsLabel() }) { chooseBars() },
            tile("🔒 Lock", { lock.kind?.let { if (it == Lock.Kind.PATTERN) "Pattern" else it.label } ?: "Off" }) {
                close()
                lock.settings()
            },
        )
        tiles.chunked(3).forEach { row ->
            box.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEach { addView(it, LinearLayout.LayoutParams(0, dp(78), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }) }
            })
        }
        status()?.let { line ->
            box.addView(TextView(activity).apply {
                text = line
                textSize = 13f
                alpha = 0.7f
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }
        dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Kindroid+ 💕")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    /** One square-ish tile of the grid: a small pink title over what's chosen, and ▾. */
    private fun tile(title: String, value: () -> String, onTap: (View) -> Unit): View {
        val words = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tileValues += words to { value() + " ▾" }
        words.text = value() + " ▾"
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(0x2A, 0xFF, 0xFF, 0xFF))
                setStroke(dp(1), Color.argb(0x66, 0xFF, 0x6F, 0xA5))
            }
            addView(TextView(activity).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                setTextColor(Color.rgb(0xFF, 0x6F, 0xA5))
            })
            addView(words)
            setOnClickListener { onTap(it) }
        }
    }

    /** A drop-down under a tile; the chosen one has a ✓. Picking saves it and updates the tiles. */
    private fun menu(anchor: View, items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
        android.widget.PopupMenu(activity, anchor).apply {
            items.forEachIndexed { i, (words, chosen) -> menu.add(0, i, i, if (chosen) "✓  $words" else words) }
            setOnMenuItemClickListener { item ->
                onPick(item.itemId)
                refreshTiles()
                true
            }
            show()
        }
    }

    private fun showLabel(): String {
        val parts = listOfNotNull("Photos".takeIf { showPhotos }, "Videos".takeIf { showVideos }, "Mine".takeIf { showMine })
        return when (parts.size) {
            0 -> "Nothing"
            3 -> "All"
            else -> parts.joinToString(" · ")
        }
    }

    private fun chooseShow() {
        val names = arrayOf("Kin's selfie photos", "Kin's video selfies (moving)", "My own pictures")
        val keys = arrayOf(PHOTOS, VIDEOS, MINE)
        val checked = booleanArrayOf(showPhotos, showVideos, showMine)
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Show in the slideshow")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> prefs.edit().putBoolean(keys[which], isChecked).apply() }
            .setPositiveButton("Done", null)
            .setOnDismissListener { refreshTiles() }
            .show()
    }

    private val barsSlim get() = prefs.getBoolean(BARS_SLIM, true)
    private val barsClear get() = prefs.getBoolean(BARS_CLEAR, true)
    private val barsHide get() = prefs.getBoolean(BARS_HIDE, true)

    private fun barsLabel(): String {
        val parts = listOfNotNull("Slim".takeIf { barsSlim }, "Clear".takeIf { barsClear }, "Hide".takeIf { barsHide })
        return if (parts.isEmpty()) "Kindroid's" else parts.joinToString(" · ")
    }

    /** The chat's top and bottom bars: slimmer, see-through, and slide away while you look. */
    private fun chooseBars() {
        val names = arrayOf("Slimmer bars", "See-through bars", "Hide them while I look (tap to bring back)")
        val keys = arrayOf(BARS_SLIM, BARS_CLEAR, BARS_HIDE)
        val checked = booleanArrayOf(barsSlim, barsClear, barsHide)
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Top and bottom bars")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> prefs.edit().putBoolean(keys[which], isChecked).apply() }
            .setPositiveButton("Done", null)
            .setOnDismissListener { refreshTiles() }
            .show()
    }

    private fun toast(words: String) = android.widget.Toast.makeText(activity, words, android.widget.Toast.LENGTH_SHORT).show()

    private fun status(): String? {
        val json = runCatching { JSONObject(lastReport) }.getOrNull()
        val lines = mutableListOf<String>()
        if (gatherer != null) lines += "🔎 Looking through the selfie gallery… $gatheredSoFar so far"
        if (json != null) {
            val fromGallery = json.optString("from") == "gallery"
            val found = listOfNotNull(
                plural(json.optInt("photos"), "photo")?.let { if (fromGallery) "$it from the gallery" else it },
                plural(json.optInt("videos"), "video"),
                plural(json.optInt("mine"), "of my pictures", "of my pictures"),
            )
            when {
                found.isNotEmpty() -> lines += "Found " + found.joinToString(", ")
                json.optString("waiting").isNotEmpty() -> lines += json.optString("waiting") + "…"
                json.optString("problem").isNotEmpty() -> lines += "⚠️ " + json.optString("problem")
                fromGallery && gatherer == null -> lines += "⚠️ No selfies found yet"
            }
            if (json.optString("problem").isNotEmpty() && found.isNotEmpty()) lines += "⚠️ " + json.optString("problem")
            val favorites = json.optInt("favorites")
            if (favoritesOnly) lines += if (json.optBoolean("noFavs") || favorites == 0) "⭐ No favorites yet, so all of them show" else "⭐ Showing your $favorites favorites"
            else if (favorites > 0) lines += "⭐ $favorites starred"
            json.optString("listProblem").takeIf { it.isNotEmpty() }?.let { lines += "($it)" }
            json.optString("videoProblem").takeIf { it.isNotEmpty() }?.let { lines += "($it)" }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun plural(n: Int, one: String, many: String = one + "s"): String? = when {
        n <= 0 -> null
        n == 1 && one.startsWith("of ") -> "1 $one"
        n == 1 -> "1 $one"
        else -> "$n $many"
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
        private const val PHOTOS = "photos"
        private const val VIDEOS = "videos"
        private const val MINE = "mine"
        private const val FAVORITES = "favorites"
        private const val BARS_SLIM = "bars_slim"
        private const val BARS_CLEAR = "bars_clear"
        private const val BARS_HIDE = "bars_hide"
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
