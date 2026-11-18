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
import android.widget.RadioButton
import android.widget.RadioGroup
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
 */
class Slides(private val activity: Activity, private val root: FrameLayout) {

    private val prefs = activity.getSharedPreferences("slides", Context.MODE_PRIVATE)

    /** Made up each run, so no page can count on it. */
    private val bridgeName = "kp" + UUID.randomUUID().toString().replace("-", "").take(10)

    /** What the page last said: how many selfies it found, or what went wrong (JSON). */
    @Volatile private var lastReport = ""

    private val script: String by lazy {
        val js = activity.assets.open("slides.js").bufferedReader().use { it.readText() }
        "(function (KP_BRIDGE) {\n$js\n})('$bridgeName');"
    }

    private val on get() = prefs.getBoolean(ON, true)
    private val order get() = prefs.getString(ORDER, null)?.takeIf { it in ORDERS.map { o -> o.first } } ?: "random"
    private val seconds get() = prefs.getInt(SECONDS, 10).takeIf { it in SECONDS_CHOICES } ?: 10

    private inner class Bridge {
        @JavascriptInterface
        fun settings(): String = JSONObject().put("on", on).put("order", order).put("seconds", seconds).toString()

        @JavascriptInterface
        fun report(json: String) {
            lastReport = json.take(500)
        }
    }

    fun attach(web: WebView) = web.addJavascriptInterface(Bridge(), bridgeName)

    /** Starts the slideshow on a Kindroid page (it runs once per page and watches for chats itself). */
    fun inject(web: WebView) = web.evaluateJavascript(script, null)

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

    /** The button only shows in a Kin's chat (not group chats or other pages). */
    fun pageChanged(url: String?) {
        button.visibility = if (isKinChat(url)) View.VISIBLE else View.GONE
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
        box.addView(heading("Order"))
        box.addView(choices(ORDERS, order) { prefs.edit().putString(ORDER, it).apply() })
        box.addView(heading("Change every"))
        box.addView(choices(SECONDS_CHOICES.map { it.toString() to secondsLabel(it) }, seconds.toString()) {
            prefs.edit().putInt(SECONDS, it.toInt()).apply()
        })
        status()?.let { line ->
            box.addView(TextView(activity).apply {
                text = line
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(14), 0, 0)
            })
        }
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Selfie slideshow 💕")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    /** What the page found last time, for the bottom of the pop-up. */
    private fun status(): String? {
        val json = runCatching { JSONObject(lastReport) }.getOrNull() ?: return null
        val problem = json.optString("problem")
        val count = json.optInt("count")
        return when {
            problem.isNotEmpty() -> "⚠️ $problem"
            count == 1 -> "Found 1 selfie"
            count > 0 -> "Found $count selfies"
            else -> null
        }
    }

    private fun heading(words: String) = TextView(activity).apply {
        text = words
        textSize = 14f
        setTextColor(Color.rgb(0xFF, 0x6F, 0xA5))
        setPadding(0, dp(16), 0, dp(2))
    }

    private fun choices(options: List<Pair<String, String>>, picked: String, onPick: (String) -> Unit) =
        RadioGroup(activity).apply {
            options.forEach { (key, label) ->
                val radio = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 16f
                    tag = key
                }
                addView(radio)
                if (key == picked) check(radio.id)
            }
            setOnCheckedChangeListener { group, id -> (group.findViewById<View>(id)?.tag as? String)?.let(onPick) }
        }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val ON = "on"
        private const val ORDER = "order"
        private const val SECONDS = "seconds"
        private const val BUTTON_AT = "button_at"
        private const val RESTING_ALPHA = 0.75f

        val ORDERS = listOf("random" to "Random", "newest" to "Newest first", "oldest" to "Oldest first")
        val SECONDS_CHOICES = listOf(5, 10, 30, 60, 300)

        fun secondsLabel(s: Int) = when {
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
