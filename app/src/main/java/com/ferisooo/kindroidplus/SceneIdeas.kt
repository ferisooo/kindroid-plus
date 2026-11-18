package com.ferisooo.kindroidplus

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * ✨ Scene ideas: the owner's own AI (Claude, ChatGPT, Gemini, Grok or DeepSeek, with their
 * own key) writes a suggestion for Kindroid's scene setting box, and "Use it" puts it in the
 * box. Kindroid's own Save button still saves it.
 *
 * The box (from kindroid.ai's code): a textarea of at most 160 characters, "location,
 * attire, activity, time, etc.", in the chat's "Set the scene" sheet.
 * The key stays on the phone (app-only storage, not backed up) and goes only to that AI.
 *
 * [putInBox] asks the page to fill the box; it answers whether it could.
 */
class SceneIdeas(private val activity: Activity, private val putInBox: (String, (Boolean) -> Unit) -> Unit) {

    private val prefs = activity.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val ai = Ai()

    val provider: Ai.Provider? get() = Ai.Provider.of(prefs.getString(PROVIDER, null))?.takeIf { keyFor(it).isNotEmpty() }

    private fun keyFor(p: Ai.Provider) = prefs.getString(KEY + p.key, null).orEmpty()
    private fun typedModel(p: Ai.Provider) = prefs.getString(MODEL + p.key, null).orEmpty()
    private fun pickedModel(p: Ai.Provider) = prefs.getString(PICKED + p.key, null).orEmpty()

    /** Opens the ideas pop-up for the scene box, [context] from the page; sets up the AI first if needed. */
    fun open(context: JSONObject?) {
        if (provider == null) setUp { open(context) } else ideas(context ?: JSONObject())
    }

    /** Pick the AI and type its key. Checks the key works before keeping it. */
    fun setUp(then: (() -> Unit)? = null) {
        var picked = Ai.Provider.of(prefs.getString(PROVIDER, null)) ?: Ai.Provider.CLAUDE
        val box = column()
        box.addView(small("Use your own AI key. It stays on this phone and only goes to that AI company. Using it may cost you a little on your AI account."))
        box.addView(heading("Which AI"))
        val keyBox = EditText(activity).apply {
            hint = "Paste your API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val modelBox = EditText(activity).apply {
            hint = "Model (leave empty: picked for you)"
            setSingleLine()
            textSize = 14f
        }
        val which = pill("") { v ->
            PopupMenu(activity, v).apply {
                Ai.Provider.entries.forEachIndexed { i, p -> menu.add(0, i, i, p.label) }
                setOnMenuItemClickListener { item ->
                    picked = Ai.Provider.entries[item.itemId]
                    (v as TextView).text = "${picked.label} ▾"
                    keyBox.setText(keyFor(picked))
                    modelBox.setText(typedModel(picked))
                    true
                }
                show()
            }
        }
        which.text = "${picked.label} ▾"
        box.addView(which)
        box.addView(heading("API key"))
        keyBox.setText(keyFor(picked))
        box.addView(keyBox)
        box.addView(heading("Model"))
        modelBox.setText(typedModel(picked))
        box.addView(modelBox)
        val status = small("")
        box.addView(status)
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Your AI ✨")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove key", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            prefs.edit().remove(KEY + picked.key).remove(PICKED + picked.key).apply()
            keyBox.setText("")
            status.text = "Key removed from this phone."
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = keyBox.text.toString().trim()
            val model = modelBox.text.toString().trim()
            if (key.isEmpty()) {
                status.text = "⚠️ Paste your key first."
                return@setOnClickListener
            }
            status.text = "Checking the key…"
            val chosen = picked
            Thread {
                val problem = runCatching { ai.check(chosen, key) }.exceptionOrNull()
                activity.runOnUiThread {
                    if (problem != null && problem.message?.startsWith("Couldn't reach") != true) {
                        status.text = "⚠️ " + (problem.message ?: "That didn't work.")
                        return@runOnUiThread
                    }
                    // A new key or a typed model: the model picked for the old one may not fit.
                    val forget = model.isNotEmpty() || keyFor(chosen) != key
                    val edit = prefs.edit()
                        .putString(PROVIDER, chosen.key)
                        .putString(KEY + chosen.key, key)
                        .putString(MODEL + chosen.key, model)
                    if (forget) edit.remove(PICKED + chosen.key)
                    edit.apply()
                    Toast.makeText(activity, if (problem == null) "Key works ✅" else "Saved (couldn't check it: no internet)", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    then?.invoke()
                }
            }.start()
        }
    }

    private fun ideas(context: JSONObject) {
        val p = provider ?: return
        val box = column()
        box.addView(small("Using ${p.label} · ").apply {
            append("change")
            setOnClickListener { setUp() }
        })
        box.addView(heading("What do you want? (you can leave it empty)"))
        val want = EditText(activity).apply {
            hint = "e.g. beach at sunset, cozy rainy night…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 3
        }
        box.addView(want)
        val result = EditText(activity).apply {
            hint = "The idea shows here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            visibility = View.GONE
        }
        val count = small("").apply { gravity = Gravity.END; visibility = View.GONE }
        result.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, n: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s?.length ?: 0
                count.text = "$n/$LIMIT"
                count.setTextColor(if (n > LIMIT) Color.rgb(0xFF, 0x80, 0x80) else Color.LTGRAY)
            }
        })
        val spinner = ProgressBar(activity).apply { visibility = View.GONE }
        val status = small("")
        box.addView(spinner, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
        box.addView(result)
        box.addView(count)
        box.addView(status)
        val name = context.optString("name")
        val current = context.optString("current")
        val recent = context.optJSONArray("recent")
        box.addView(small(buildString {
            append("Sent to ${p.label}: what you typed")
            if (current.isNotBlank()) append(", the current scene")
            if (recent != null && recent.length() > 0) append(", the last few messages")
            if (name.isNotBlank()) append(", and $name's name")
            append(".")
        }).apply { alpha = 0.55f })
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("✨ Scene ideas")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("Write it ✨", null)
            .setNeutralButton("Use it", null)
            .setNegativeButton("Close", null)
            .show()
        val write = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val use = dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply { visibility = View.GONE }
        write.setOnClickListener {
            write.isEnabled = false
            spinner.visibility = View.VISIBLE
            status.text = "Writing…"
            val asked = prompt(want.text.toString().trim(), name, current, recent)
            Thread {
                val typed = typedModel(p)
                val outcome = runCatching {
                    ai.write(p, keyFor(p), typed.ifEmpty { pickedModel(p) }, SYSTEM, asked, typed = typed.isNotEmpty()) { model ->
                        if (typed.isEmpty()) prefs.edit().putString(PICKED + p.key, model).apply()
                    }
                }
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    spinner.visibility = View.GONE
                    write.isEnabled = true
                    outcome.onSuccess { reply ->
                        val text = tidy(reply.text)
                        if (text.isEmpty()) {
                            status.text = "⚠️ The AI didn't write anything. Try again."
                        } else {
                            result.setText(text)
                            result.visibility = View.VISIBLE
                            count.visibility = View.VISIBLE
                            use.visibility = View.VISIBLE
                            write.text = "Again ✨"
                            status.text = ""
                        }
                    }.onFailure { status.text = "⚠️ " + (it.message ?: "That didn't work.") }
                }
            }.start()
        }
        use.setOnClickListener {
            val text = result.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            putInBox(text) { done ->
                if (!done) {
                    val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("Scene", text))
                    Toast.makeText(activity, "Copied! Paste it in the scene box.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "Put in the box. Tap Save to keep it 💕", Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
        }
        want.requestFocus()
    }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(6), dp(22), dp(4))
    }

    private fun heading(words: String) = TextView(activity).apply {
        text = words
        textSize = 14f
        setTextColor(Color.rgb(0xFF, 0x6F, 0xA5))
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun small(words: String) = TextView(activity).apply {
        text = words
        textSize = 13f
        alpha = 0.8f
        setPadding(0, dp(8), 0, 0)
    }

    private fun pill(words: String, onTap: (View) -> Unit) = TextView(activity).apply {
        text = words
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
            setStroke(dp(1), Color.argb(0x66, 0xFF, 0x6F, 0xA5))
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onTap(it) }
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        /** Kindroid's scene box takes at most this many characters. */
        const val LIMIT = 160

        private const val PROVIDER = "provider"
        private const val KEY = "key:"
        private const val MODEL = "model:"
        private const val PICKED = "picked:"

        /** Kindroid's own words for the box, plus the rules it needs. */
        val SYSTEM = """
            You write the "scene setting" for a chat with an AI companion in the Kindroid app.
            Kindroid describes it as: "Set the scene for this conversation. Your Kindroid uses the setting as ambient context: they'll behave like the conversation is actually happening there." It covers location, attire, activity, time and mood.
            Write exactly one scene setting of at most 150 characters (the box holds 160).
            Plain text in the present tense, like a stage direction, for example: Cozy cabin at night, rain on the windows; both in sweaters, sharing cocoa by the fire.
            Reply with only the scene text: no title, no quotes, no explanation, no options.
        """.trimIndent()

        fun prompt(want: String, name: String, current: String, recent: org.json.JSONArray?): String = buildString {
            if (name.isNotBlank()) append("Companion's name: ").append(name.take(60)).append('\n')
            append("Current scene: ").append(current.take(300).ifBlank { "(none)" }).append('\n')
            if (recent != null && recent.length() > 0) {
                append("Last messages in the chat (oldest first):\n")
                for (i in 0 until recent.length()) {
                    val m = recent.optJSONObject(i) ?: continue
                    append(if (m.optBoolean("mine")) "Me: " else "Companion: ").append(m.optString("text").replace(Regex("\\s+"), " ").take(300)).append('\n')
                }
            }
            append('\n')
            append("What I'd like: ").append(want.ifBlank { "A new scene that fits where the chat is going." })
        }

        /** The AI's words as a box-ready scene: one line, no wrapping quotes, at most [LIMIT] characters. */
        fun tidy(text: String): String {
            var t = text.replace(Regex("\\s+"), " ").trim()
            t = t.removePrefix("Scene setting:").removePrefix("Scene:").trim()
            while (t.length >= 2 && t.first() in "\"'“‘" && t.last() in "\"'”’") t = t.substring(1, t.length - 1).trim()
            if (t.length <= LIMIT) return t
            // Too long: end at the last full sentence or word that fits.
            val cut = t.substring(0, LIMIT)
            val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?', ';'))
            return if (end >= 60) cut.substring(0, end + 1) else cut.substringBeforeLast(' ').trimEnd(',', ';', ' ')
        }
    }
}
