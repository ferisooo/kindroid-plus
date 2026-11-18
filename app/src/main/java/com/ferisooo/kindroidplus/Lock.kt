package com.ferisooo.kindroidplus

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.hypot
import kotlin.math.min

/**
 * The app lock: a PIN, a pattern (sliding over dots) or a password, asked for when the app
 * opens and when you come back to it. Only a salted PBKDF2 hash of it is kept.
 *
 * Forgot it? "Forgot?" starts over: Kindroid+ forgets everything on this phone (the Kindroid
 * login, the lock, AI keys, pictures, settings). The Kindroid account and chats live on
 * Kindroid, so logging in again brings them back. No reinstall needed.
 */
class Lock(private val activity: Activity, private val root: FrameLayout, private val startOver: () -> Unit) {

    enum class Kind(val key: String, val label: String, val noun: String) {
        PIN("pin", "PIN", "PIN"),
        PATTERN("pattern", "Pattern (slide over dots)", "pattern"),
        PASSWORD("password", "Password", "password");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    private val prefs = activity.getSharedPreferences("lock", Context.MODE_PRIVATE)

    val kind: Kind? get() = Kind.of(prefs.getString(KIND, null))

    /** How long the app can be away before it locks again. */
    val graceMs: Long get() = prefs.getLong(GRACE, 0)

    /** The lock screen on top of everything, while it's up. */
    private var screen: View? = null
    val isShowing get() = screen != null

    /** Shows the lock screen (if there's a lock). */
    fun lockNow() {
        val k = kind ?: return
        if (screen != null) return
        hideRecentsPicture(true)
        screen = pad(k, title = "🔒 Kindroid+", hint = "Enter your ${k.noun}", cancel = null, forgot = true) { secret, say ->
            check(secret, say) { ok -> if (ok) unlock() }
        }.also { root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }

    private fun unlock() {
        val s = screen ?: return
        screen = null
        hideKeyboard(s)
        root.removeView(s)
    }

    /** The phone's back button while locked: leaves the app instead. */
    fun onBack(): Boolean {
        if (screen == null) return false
        activity.moveTaskToBack(true)
        return true
    }

    // ---- Checking ----

    /**
     * Checks [secret] off the main thread (the hash takes a moment on purpose). Five wrong
     * tries in a row mean a wait: 30 seconds, then longer each time, up to 5 minutes.
     */
    private fun check(secret: String, say: (String) -> Unit, done: (Boolean) -> Unit) {
        val waitLeft = prefs.getLong(WAIT_UNTIL, 0) - System.currentTimeMillis()
        if (waitLeft > 0) {
            say("Too many wrong tries. Wait ${(waitLeft + 999) / 1000} s.")
            done(false)
            return
        }
        val salt = prefs.getString(SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val want = prefs.getString(HASH, null)
        Thread {
            val ok = salt != null && want != null && hash(secret, salt) == want
            activity.runOnUiThread {
                if (ok) {
                    prefs.edit().putInt(WRONG, 0).apply()
                } else {
                    val wrong = prefs.getInt(WRONG, 0) + 1
                    val edit = prefs.edit().putInt(WRONG, wrong)
                    if (wrong % 5 == 0) {
                        val wait = min(30_000L shl (wrong / 5 - 1).coerceAtMost(4), 5 * 60_000L)
                        edit.putLong(WAIT_UNTIL, System.currentTimeMillis() + wait)
                        say("Too many wrong tries. Wait ${wait / 1000} s.")
                    } else {
                        say("That's not it. Try again.")
                    }
                    edit.apply()
                }
                done(ok)
            }
        }.start()
    }

    private fun hash(secret: String, salt: ByteArray): String {
        val spec = PBEKeySpec(Normalizer.normalize(secret, Normalizer.Form.NFC).toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun save(k: Kind, secret: String, grace: Long) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Thread {
            val h = hash(secret, salt)
            activity.runOnUiThread {
                prefs.edit()
                    .putString(KIND, k.key)
                    .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(HASH, h)
                    .putInt(LENGTH, if (k == Kind.PIN) secret.length else 0)
                    .putLong(GRACE, grace)
                    .putInt(WRONG, 0)
                    .remove(WAIT_UNTIL)
                    .apply()
                hideRecentsPicture(true)
                android.widget.Toast.makeText(activity, "App lock is on 🔒", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** With a lock on, the recent-apps list shows no picture of the chat (Android 13+). */
    private fun hideRecentsPicture(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!(hide && kind != null))
    }

    // ---- Setting it up ----

    /** The 🔒 choice in the Kindroid+ pop-up: off, PIN, pattern or password, and when it locks. */
    fun settings() {
        val current = kind
        val names = arrayOf("Off") + Kind.entries.map { it.label } + arrayOf("When it locks…")
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("🔒 App lock" + (current?.let { ": ${it.label}" } ?: ": off"))
            .setItems(names) { _, which ->
                when (which) {
                    0 -> if (current != null) confirmThen("Enter your ${current.noun} to turn the lock off") { turnOff() }
                    names.size - 1 -> chooseGrace()
                    else -> {
                        val k = Kind.entries[which - 1]
                        if (current != null) confirmThen("Enter your current ${current.noun} first") { choose(k) } else choose(k)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseGrace() {
        val options = listOf(0L to "Right away", 60_000L to "After 1 minute away", 5 * 60_000L to "After 5 minutes away")
        val now = options.indexOfFirst { it.first == graceMs }.coerceAtLeast(0)
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Lock when I leave the app")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), now) { d, which ->
                prefs.edit().putLong(GRACE, options[which].first).apply()
                d.dismiss()
            }
            .show()
    }

    private fun turnOff() {
        prefs.edit().clear().apply()
        hideRecentsPicture(false)
        android.widget.Toast.makeText(activity, "App lock is off", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Asks for the current lock, then does [then]. */
    private fun confirmThen(hint: String, then: () -> Unit) {
        val k = kind ?: return then()
        var page: View? = null
        page = pad(k, title = "🔒 Kindroid+", hint = hint, cancel = { close(page) }, forgot = true) { secret, say ->
            check(secret, say) { ok ->
                if (ok) {
                    close(page)
                    then()
                }
            }
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Pick the new one twice, then save it. */
    private fun choose(k: Kind) {
        var page: View? = null
        val first = when (k) {
            Kind.PIN -> "Choose a PIN (4 to 12 numbers)"
            Kind.PATTERN -> "Draw a pattern (join at least 4 dots)"
            Kind.PASSWORD -> "Choose a password (at least 4 characters)"
        }
        var chosen: String? = null
        page = pad(k, title = "New ${k.noun}", hint = first, cancel = { close(page) }, forgot = false, setup = true) { secret, say ->
            val problem = when {
                k == Kind.PIN && secret.length !in 4..12 -> "Use 4 to 12 numbers."
                k == Kind.PATTERN && secret.split('-').size < 4 -> "Join at least 4 dots."
                k == Kind.PASSWORD && secret.codePointCount(0, secret.length) < 4 -> "Use at least 4 characters."
                else -> null
            }
            when {
                problem != null -> say(problem)
                chosen == null -> {
                    chosen = secret
                    say("Now once more to be sure")
                }
                chosen == secret -> {
                    close(page)
                    save(k, secret, graceMs)
                }
                else -> {
                    chosen = null
                    say("They didn't match. Start again: $first")
                }
            }
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun close(page: View?) {
        page ?: return
        hideKeyboard(page)
        root.removeView(page)
    }

    private fun forgot() {
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Forgot it?")
            .setMessage(
                "You can start over: Kindroid+ forgets everything on this phone (your Kindroid login, the lock, " +
                    "your AI key, your pictures and settings).\n\nYour Kindroid account and chats are safe on Kindroid. " +
                    "Just log in again after."
            )
            .setPositiveButton("Start over") { _, _ -> startOver() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- The screen ----

    /**
     * A full screen for typing the lock: [title], a [hint] line that also shows problems,
     * the PIN pad, pattern or password box, and Cancel / Forgot? when asked for.
     * [entered] gets what was typed or drawn, and a way to say something back.
     */
    private fun pad(
        k: Kind, title: String, hint: String, cancel: (() -> Unit)?, forgot: Boolean, setup: Boolean = false,
        entered: (String, (String) -> Unit) -> Unit,
    ): View {
        val page = FrameLayout(activity).apply {
            setBackgroundColor(BACKGROUND)
            isClickable = true // nothing under it can be touched
            isFocusable = true
        }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        column.addView(text(title, 24f, Color.WHITE))
        val line = text(hint, 15f, Color.argb(0xCC, 0xFF, 0xFF, 0xFF)).apply { setPadding(0, dp(10), 0, dp(18)) }
        column.addView(line)
        val say: (String) -> Unit = { line.text = it }
        when (k) {
            Kind.PIN -> pinPad(column, if (setup) 0 else prefs.getInt(LENGTH, 0)) { entered(it, say) }
            Kind.PATTERN -> column.addView(PatternView(activity) { entered(it, say) }, LinearLayout.LayoutParams(dp(280), dp(280)))
            Kind.PASSWORD -> passwordBox(column) { entered(it, say) }
        }
        val bottom = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (cancel != null) bottom.addView(link("Cancel") { cancel() })
        if (forgot) bottom.addView(link("Forgot?") { forgot() })
        column.addView(bottom)
        page.addView(ScrollingCenter(activity, column), FrameLayout.LayoutParams(MATCH, MATCH))
        return page
    }

    /** Centres the lock's column, and lets it scroll if the keyboard leaves too little room. */
    private class ScrollingCenter(context: Context, child: View) : android.widget.ScrollView(context) {
        init {
            isFillViewport = true
            addView(FrameLayout(context).apply {
                addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Dots for what's typed, then 1-9, ⌫, 0, ✓. A saved PIN opens as soon as its length is typed. */
    private fun pinPad(column: LinearLayout, length: Int, done: (String) -> Unit) {
        val typed = StringBuilder()
        val dots = text("", 26f, ACCENT).apply { minHeight = dp(40) }
        fun show() {
            dots.text = if (typed.isEmpty()) " " else "●".repeat(typed.length).toList().joinToString(" ")
        }
        show()
        column.addView(dots)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.chunked(3).forEach { row ->
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            row.forEach { key ->
                line.addView(TextView(activity).apply {
                    text = key
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(0x22, 0xFF, 0xFF, 0xFF))
                    }
                    setOnClickListener {
                        when (key) {
                            "⌫" -> if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                            "✓" -> if (typed.isNotEmpty()) { done(typed.toString()); typed.clear() }
                            else -> if (typed.length < 12) typed.append(key)
                        }
                        show()
                        if (length > 0 && typed.length == length) {
                            done(typed.toString())
                            typed.clear()
                            show()
                        }
                    }
                }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) })
            }
            column.addView(line)
        }
    }

    private fun passwordBox(column: LinearLayout, done: (String) -> Unit) {
        val box = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Password"
            setSingleLine()
        }
        val send = {
            val t = box.text.toString()
            if (t.isNotEmpty()) {
                box.setText("")
                done(t)
            }
        }
        box.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
        }
        column.addView(box, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(link("Open") { send() })
        box.post {
            box.requestFocus()
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(box, 0)
        }
    }

    /** 3 by 3 dots: slide a finger over them. Gives the dots in order, like "0-4-8-5". */
    private class PatternView(context: Context, private val done: (String) -> Unit) : View(context) {
        private val picked = mutableListOf<Int>()
        private var fingerX = -1f
        private var fingerY = -1f
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x88, 0xFF, 0xFF, 0xFF) }
        private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            strokeWidth = resources.displayMetrics.density * 5
            strokeCap = Paint.Cap.ROUND
            alpha = 0xCC
        }

        private fun center(i: Int): Pair<Float, Float> {
            val cell = min(width, height) / 3f
            return (i % 3 + 0.5f) * cell to (i / 3 + 0.5f) * cell
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) / 3f
            for (i in 1 until picked.size) {
                val (ax, ay) = center(picked[i - 1])
                val (bx, by) = center(picked[i])
                canvas.drawLine(ax, ay, bx, by, path)
            }
            if (picked.isNotEmpty() && fingerX >= 0) {
                val (ax, ay) = center(picked.last())
                canvas.drawLine(ax, ay, fingerX, fingerY, path)
            }
            for (i in 0 until 9) {
                val (x, y) = center(i)
                canvas.drawCircle(x, y, r * if (i in picked) 0.14f else 0.09f, if (i in picked) on else dot)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    picked.clear()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    hit(e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> hit(e.x, e.y)
                MotionEvent.ACTION_UP -> {
                    fingerX = -1f
                    if (picked.isNotEmpty()) done(picked.joinToString("-"))
                    postDelayed({ picked.clear(); invalidate() }, 300)
                }
                MotionEvent.ACTION_CANCEL -> {
                    fingerX = -1f
                    picked.clear()
                }
            }
            invalidate()
            return true
        }

        private fun hit(x: Float, y: Float) {
            fingerX = x
            fingerY = y
            val r = min(width, height) / 3f * 0.35f
            for (i in 0 until 9) {
                if (i in picked) continue
                val (cx, cy) = center(i)
                if (hypot(x - cx, y - cy) <= r) {
                    picked += i
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
        }
    }

    private fun text(words: String, size: Float, color: Int) = TextView(activity).apply {
        text = words
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(color)
    }

    private fun link(words: String, onTap: () -> Unit) = TextView(activity).apply {
        text = words
        textSize = 16f
        setTextColor(ACCENT)
        setPadding(dp(18), dp(18), dp(18), dp(10))
        setOnClickListener { onTap() }
    }

    private fun hideKeyboard(v: View) {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val KIND = "kind"
        private const val SALT = "salt"
        private const val HASH = "hash"
        private const val LENGTH = "length"
        private const val GRACE = "grace_ms"
        private const val WRONG = "wrong"
        private const val WAIT_UNTIL = "wait_until"
        private const val ITERATIONS = 120_000
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val BACKGROUND = Color.rgb(0x12, 0x10, 0x16)
        private val ACCENT = Color.rgb(0xFF, 0x6F, 0xA5)

        @Suppress("unused")
        private fun now() = SystemClock.elapsedRealtime()
    }
}
