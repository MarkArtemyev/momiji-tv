package app.momiji.tv.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApiException(
    val status: Int,
    message: String,
    val retryAfter: Long? = null,
    val daily: Boolean = false,
    val reset: Long? = null,
) : Exception(message) {

    val busy get() = status in BUSY

    fun explain(): String {
        val text = message.orEmpty().lowercase()
        return when {
            "location" in text && "not supported" in text -> "Gemini не пускает из региона. Включи VPN на телевизоре."
            status in setOf(400, 401, 403) && ("api key" in text || "api_key" in text || "permission" in text) -> "Ключ Gemini не подходит. Проверь его в настройках пульта."
            status == 429 -> {
                val head = if (daily) "Бесплатный дневной лимит Gemini кончился на всех моделях." else "Слишком много запросов к Gemini подряд."
                val tail = reset?.let { " Снова заработает в ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))}." }.orEmpty()
                head + tail
            }
            status == 0 -> "Нет связи с Gemini."
            else -> "Gemini ответил ошибкой $status."
        }
    }

    companion object {
        val BUSY = setOf(0, 429, 500, 502, 503, 504)
    }
}

class Gemini(private val prefs: AgentPrefs) {

    @Volatile
    private var thinkingOk = true
    private val slow = HashMap<String, Long>()

    fun ask(system: String, contents: JSONArray): JSONObject {
        val chain = prefs.chain
        var last: ApiException? = null
        var reset: Long? = null
        var daily = false
        chain.forEachIndexed { i, model ->
            val now = System.currentTimeMillis()
            val limited = prefs.limitedUntil(model)
            if (limited > now) {
                reset = minOf(reset ?: limited, limited)
                daily = daily || limited - now > 3_600_000
                return@forEachIndexed
            }
            if ((slow[model] ?: 0L) > now) return@forEachIndexed
            val timeout = if (i == 0 && chain.size > 1) PRIMARY_TIMEOUT else 90_000
            try {
                return request(model, system, contents, timeout)
            } catch (e: ApiException) {
                last = e
                when {
                    e.status == 429 -> {
                        val until = now + (e.retryAfter ?: 60L) * 1000
                        prefs.markLimited(model, until)
                        reset = minOf(reset ?: until, until)
                        daily = daily || e.daily
                    }
                    e.busy -> if (e.status == 0 && i == 0) slow[model] = now + 10 * 60_000
                    else -> throw e
                }
            }
        }
        val error = last
        if (error == null || error.status == 429) throw ApiException(429, "quota", daily = daily || error == null, reset = reset)
        throw error
    }

    private fun request(model: String, system: String, contents: JSONArray, timeout: Int): JSONObject {
        while (true) {
            val config = JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject(SCHEMA))
            val thinking = if (thinkingOk) thinkingConfig(model, prefs.thinking) else null
            if (thinking != null) config.put("thinkingConfig", thinking)
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", config)
            try {
                return parse(post("/models/$model:generateContent", body, timeout))
            } catch (e: ApiException) {
                if (thinking != null && e.status == 400 && e.message.orEmpty().contains("think", ignoreCase = true)) {
                    thinkingOk = false
                    continue
                }
                throw e
            }
        }
    }

    private fun post(path: String, body: JSONObject, timeout: Int): JSONObject {
        val conn = URL(API + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = timeout
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", prefs.key)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw error(code, text)
            return JSONObject(text)
        } catch (e: IOException) {
            throw ApiException(0, e.message ?: "нет связи")
        } finally {
            conn.disconnect()
        }
    }

    private fun error(code: Int, text: String): ApiException = try {
        val err = JSONObject(text).getJSONObject("error")
        var retry: Long? = null
        var daily = false
        val details = err.optJSONArray("details") ?: JSONArray()
        for (i in 0 until details.length()) {
            val detail = details.getJSONObject(i)
            val kind = detail.optString("@type")
            if (kind.endsWith("RetryInfo")) {
                retry = Regex("^([\\d.]+)s").find(detail.optString("retryDelay"))?.groupValues?.get(1)?.toDouble()?.toLong()
            } else if (kind.endsWith("QuotaFailure")) {
                val violations = detail.optJSONArray("violations") ?: JSONArray()
                for (j in 0 until violations.length()) {
                    if ("PerDay" in violations.getJSONObject(j).optString("quotaId")) daily = true
                }
            }
        }
        ApiException(code, err.optString("message"), retry, daily)
    } catch (e: Exception) {
        ApiException(code, text.take(200))
    }

    private fun parse(data: JSONObject): JSONObject {
        val candidates = data.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) throw ApiException(200, "модель не ответила")
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (!part.optBoolean("thought")) text.append(part.optString("text"))
        }
        val clean = text.toString().trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(clean)
        } catch (e: Exception) {
            throw ApiException(200, "модель ответила не JSON")
        }
    }

    private fun thinkingConfig(model: String, level: String): JSONObject? = when {
        level == "off" -> null
        Regex("gemini-[3-9]").containsMatchIn(model) -> JSONObject().put("thinkingLevel", level)
        model.startsWith("gemini-2.5") -> JSONObject().put("thinkingBudget", if (level == "high") 8192 else 1024)
        else -> null
    }

    private companion object {
        const val API = "https://generativelanguage.googleapis.com/v1beta"
        const val PRIMARY_TIMEOUT = 12_000

        const val SCHEMA = """{
  "type": "OBJECT",
  "properties": {
    "heard": {"type": "STRING"},
    "thought": {"type": "STRING"},
    "actions": {
      "type": "ARRAY",
      "items": {
        "type": "OBJECT",
        "properties": {
          "type": {"type": "STRING", "enum": ["keys", "click_text", "open_app", "open_url", "search", "type", "wait"]},
          "keys": {"type": "ARRAY", "items": {"type": "STRING"}},
          "count": {"type": "INTEGER"},
          "package": {"type": "STRING"},
          "url": {"type": "STRING"},
          "query": {"type": "STRING"},
          "text": {"type": "STRING"},
          "submit": {"type": "BOOLEAN"},
          "seconds": {"type": "NUMBER"}
        },
        "required": ["type"]
      }
    },
    "done": {"type": "BOOLEAN"},
    "say": {"type": "STRING"}
  },
  "required": ["heard", "thought", "actions", "done", "say"],
  "propertyOrdering": ["heard", "thought", "actions", "done", "say"]
}"""
    }
}
