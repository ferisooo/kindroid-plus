package com.ferisooo.kindroidplus

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Talks to the AI the owner picked, with their own key, straight from the phone
 * (never through the web page). Plain HTTPS, no libraries.
 *
 * Claude uses Anthropic's Messages API. ChatGPT, Grok and DeepSeek use the same
 * "chat completions" shape; Gemini uses Google's generateContent.
 *
 * Other companies rename their models often, so for them the app asks the AI company
 * which models the key can use and picks one ([pickModel]); if that ever fails, a
 * model that went away is picked again once. The owner can also type a model name.
 */
class Ai(private val bases: Map<Provider, String> = BASES) {

    enum class Provider(val key: String, val label: String) {
        CLAUDE("claude", "Claude"),
        OPENAI("openai", "ChatGPT (OpenAI)"),
        GEMINI("gemini", "Gemini (Google)"),
        GROK("grok", "Grok (X)"),
        DEEPSEEK("deepseek", "DeepSeek");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /** Something went wrong, in words a person can read. [modelGone]: the model name isn't there. */
    class Problem(message: String, val modelGone: Boolean = false) : Exception(message)

    /** What the AI wrote, and which model wrote it. */
    data class Reply(val text: String, val model: String)

    /**
     * Writes a reply to [prompt] following [system]. [model] blank means "pick one";
     * [typed] says the owner typed it (then it's never swapped for another). [remember] is
     * told the model that worked, so the next call skips the picking.
     */
    fun write(
        provider: Provider, key: String, model: String?, system: String, prompt: String,
        typed: Boolean = false, remember: (String) -> Unit = {},
    ): Reply {
        var use = model?.trim().orEmpty().ifEmpty { if (provider == Provider.CLAUDE) CLAUDE_MODEL else pickFor(provider, key) }
        return try {
            ask(provider, key, use, system, prompt).also { remember(use) }
        } catch (p: Problem) {
            if (!p.modelGone || provider == Provider.CLAUDE || typed) throw p
            // The saved or picked model went away: pick again from what the key can use now.
            use = pickFor(provider, key, avoid = use)
            ask(provider, key, use, system, prompt).also { remember(use) }
        }
    }

    /** Checks the key works (lists models, which costs nothing). Gives how many models it can use. */
    fun check(provider: Provider, key: String): Int = models(provider, key).size

    private fun pickFor(provider: Provider, key: String, avoid: String? = null): String {
        val listed = runCatching { models(provider, key) }.getOrElse { if (it is Problem && it.message?.startsWith(KEY_WORDS) == true) throw it else emptyList() }
        return pickModel(provider, listed.filter { it != avoid }) ?: FALLBACK_MODELS.getValue(provider)
    }

    fun models(provider: Provider, key: String): List<String> {
        val base = bases.getValue(provider)
        val json = when (provider) {
            Provider.CLAUDE -> call("GET", "$base/v1/models?limit=100", claudeHeaders(key), null)
            Provider.GEMINI -> call("GET", "$base/v1beta/models?pageSize=1000", mapOf("x-goog-api-key" to key), null)
            Provider.DEEPSEEK -> call("GET", "$base/models", bearer(key), null)
            else -> call("GET", "$base/v1/models", bearer(key), null)
        }
        if (provider == Provider.GEMINI) {
            val list = json.optJSONArray("models") ?: JSONArray()
            return (0 until list.length()).mapNotNull { i ->
                val m = list.optJSONObject(i) ?: return@mapNotNull null
                val methods = m.optJSONArray("supportedGenerationMethods")
                val writes = methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                m.optString("name").removePrefix("models/").takeIf { writes && it.isNotEmpty() }
            }
        }
        val data = json.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
    }

    private fun ask(provider: Provider, key: String, model: String, system: String, prompt: String): Reply {
        val base = bases.getValue(provider)
        return when (provider) {
            Provider.CLAUDE -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", 16000)
                    // A short piece of writing: low effort is plenty and quicker.
                    .put("output_config", JSONObject().put("effort", "low"))
                    // If Claude's safety checks turn the request down, another Claude model tries it.
                    .put("fallbacks", "default")
                    .put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                val json = call("POST", "$base/v1/messages", claudeHeaders(key) + ("anthropic-beta" to "server-side-fallback-2026-07-01"), body.toString())
                if (json.optString("stop_reason") == "refusal") throw Problem("Claude didn't want to write that one. Try different words.")
                val content = json.optJSONArray("content") ?: JSONArray()
                val text = (0 until content.length()).mapNotNull { i ->
                    content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.joinToString("")
                Reply(text, json.optString("model").ifEmpty { model })
            }
            Provider.GEMINI -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                val url = "$base/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"
                val json = call("POST", url, mapOf("x-goog-api-key" to key), body.toString())
                json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
                    throw Problem("Gemini didn't want to write that one ($it). Try different words.")
                }
                val first = json.optJSONArray("candidates")?.optJSONObject(0)
                val parts = first?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val text = (0 until parts.length()).mapNotNull { i ->
                    parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought") }?.optString("text")
                }.joinToString("")
                if (text.isBlank() && first?.optString("finishReason") in listOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST")) {
                    throw Problem("Gemini didn't want to write that one. Try different words.")
                }
                Reply(text, model)
            }
            else -> {
                // ChatGPT, Grok and DeepSeek: the same "chat completions" shape.
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                // DeepSeek's own advice for creative writing is a high temperature (1.5); left at its
                // default it wrote nearly the same scene every time. (OpenAI's newer models and Grok's
                // reasoning ones refuse other temperatures, so only DeepSeek gets one.)
                if (provider == Provider.DEEPSEEK) body.put("temperature", 1.3)
                val path = if (provider == Provider.DEEPSEEK) "/chat/completions" else "/v1/chat/completions"
                val json = call("POST", base + path, bearer(key), body.toString())
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                if (choice?.optString("finish_reason") == "content_filter") {
                    throw Problem("${provider.label} didn't want to write that one. Try different words.")
                }
                Reply(choice?.optJSONObject("message")?.optString("content").orEmpty(), json.optString("model").ifEmpty { model })
            }
        }
    }

    private fun claudeHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2026-10-21")

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    private fun call(method: String, url: String, headers: Map<String, String>, body: String?): JSONObject {
        val c = java.net.URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw explain(code, text)
            return runCatching { JSONObject(text) }.getOrElse { throw Problem("The AI sent back something unexpected.") }
        } catch (e: java.io.IOException) {
            throw Problem("Couldn't reach the AI. Check your internet.")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** Claude's model: the current Opus. */
        const val CLAUDE_MODEL = "claude-opus-5-5"

        private const val KEY_WORDS = "The key didn't work"

        val BASES = mapOf(
            Provider.CLAUDE to "https://api.anthropic.com",
            Provider.OPENAI to "https://api.openai.com",
            Provider.GEMINI to "https://generativelanguage.googleapis.com",
            Provider.GROK to "https://api.x.ai",
            Provider.DEEPSEEK to "https://api.deepseek.com",
        )

        /** Used only when the model list can't be read. */
        val FALLBACK_MODELS = mapOf(
            Provider.CLAUDE to CLAUDE_MODEL,
            Provider.OPENAI to "gpt-5-mini",
            Provider.GEMINI to "gemini-flash-latest",
            Provider.GROK to "grok-4",
            Provider.DEEPSEEK to "deepseek-chat",
        )

        /** The AI's own error words, kept short, after plain words about what it means. */
        fun explain(code: Int, body: String): Problem {
            val said = runCatching {
                val json = JSONObject(body)
                val err = json.opt("error")
                when (err) {
                    is JSONObject -> err.optString("message")
                    is String -> err
                    else -> json.optString("message")
                }
            }.getOrNull().orEmpty().replace(Regex("\\s+"), " ").trim().take(160)
            val modelGone = code == 404 || (code == 400 && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(said) &&
                Regex("not (found|exist|support|available)|invalid|unknown|deprecat|does not have access", RegexOption.IGNORE_CASE).containsMatchIn(said))
            val words = when {
                code == 401 || (code == 403 && !modelGone) -> "$KEY_WORDS (wrong, or turned off)."
                code == 402 -> "Your AI account is out of credit."
                code == 429 -> "The AI says too many tries, or no credit left. Try again later."
                modelGone -> "That AI model isn't there any more."
                code >= 500 -> "The AI is busy or having trouble. Try again in a bit."
                else -> "The AI said no ($code)."
            }
            return Problem(if (said.isNotEmpty()) "$words ($said)" else words, modelGone)
        }

        /**
         * Picks a model from the ones the key can use: the newest general one. Leaves out
         * models for pictures, voice, search and the like. Null when nothing fits.
         */
        fun pickModel(provider: Provider, ids: List<String>): String? {
            val notForWriting = Regex("image|vision|voice|audio|imagine|transcri|tts|embed|realtime|search|moderation|codex|instruct|dall|whisper|live")
            return when (provider) {
                Provider.CLAUDE -> null
                Provider.OPENAI -> ids.filter { Regex("^gpt-\\d+(\\.\\d+)?(-mini)?$").matches(it) }
                    .maxWithOrNull(compareBy<String>({ version(it).first }, { version(it).second }, { if (it.endsWith("-mini")) 0 else 1 }))
                Provider.GROK -> ids.filter { it.startsWith("grok-") && !notForWriting.containsMatchIn(it) }
                    .maxWithOrNull(compareBy<String>({ version(it).first }, { version(it).second }, { -it.length }))
                Provider.DEEPSEEK -> ids.firstOrNull { "flash" in it } ?: ids.firstOrNull { it == "deepseek-chat" }
                    ?: ids.firstOrNull { "chat" in it || "v4" in it } ?: ids.firstOrNull { "reasoner" !in it } ?: ids.firstOrNull()
                Provider.GEMINI -> ids.filter {
                    it.startsWith("gemini-") && "flash" in it &&
                        !Regex("lite|thinking|exp|preview|native|latest").containsMatchIn(it) && !notForWriting.containsMatchIn(it)
                }.maxWithOrNull(compareBy<String>({ version(it).first }, { version(it).second }, { -it.length }))
                    ?: ids.firstOrNull { it == "gemini-flash-latest" }
            }
        }

        /** "grok-4.7" → (4, 7), "grok-4-1-fast" → (4, 1), "gemini-3.8-flash" → (3, 8), "gpt-5" → (5, 0). */
        fun version(id: String): Pair<Int, Int> {
            val m = Regex("^[a-z]+-(\\d+)(?:[.-](\\d+)(?![a-z]))?").find(id) ?: return 0 to 0
            return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
        }
    }
}
