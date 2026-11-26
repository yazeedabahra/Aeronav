package com.yvora.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Personal-use lab. No embedded keys, no redirects, no logs, no automatic calls. */
internal object AiProviderClient {
    private fun msg(role:String, content:String) = JSONObject().put("role",role).put("content",content)

    fun plan(provider: AiProvider, model: String, key: String, request: String): String =
        this.request(provider,model,key,request,AiCommandGate.INSTRUCTION,1000)

    fun diagnose(provider: AiProvider, model: String, key: String, report: String): String =
        request(provider,model,key,report,DiagnosticGate.INSTRUCTION,3000)

    private fun request(provider: AiProvider, model: String, key: String, request: String, instructions: String, maxLength: Int): String {
        require(model.matches(Regex("[A-Za-z0-9._:-]{1,100}"))) { "Invalid model ID" }
        require(key.isNotBlank() && key.length <= 2048 && !key.any { it.isWhitespace() }) { "Invalid API key" }
        require(request.isNotBlank() && request.length <= maxLength) { "Invalid request length" }
        val url: String
        val payload: JSONObject
        when(provider) {
            AiProvider.OPENAI, AiProvider.DEEPSEEK -> {
                url = if(provider == AiProvider.OPENAI) "https://api.openai.com/v1/chat/completions" else "https://api.deepseek.com/chat/completions"
                payload = JSONObject().put("model",model).put("messages",
                    JSONArray().put(msg("system",instructions)).put(msg("user",request)))
                if (provider == AiProvider.OPENAI) payload.put("max_completion_tokens",512)
                else payload.put("max_tokens",512)
            }
            AiProvider.CLAUDE -> {
                url = "https://api.anthropic.com/v1/messages"
                payload = JSONObject().put("model",model).put("system",instructions)
                    .put("max_tokens",512).put("messages",JSONArray().put(msg("user",request)))
            }
            AiProvider.GEMINI -> {
                require(model.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "Invalid Gemini model ID" }
                url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                payload = JSONObject().put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",instructions))))
                    .put("contents",JSONArray().put(JSONObject().put("role","user").put("parts",JSONArray().put(JSONObject().put("text",request)))))
                    .put("generationConfig",JSONObject().put("maxOutputTokens",512))
            }
        }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=12000
            connection.readTimeout=25000
            connection.requestMethod="POST"
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            when(provider) {
                AiProvider.CLAUDE -> {
                    connection.setRequestProperty("x-api-key",key)
                    connection.setRequestProperty("anthropic-version","2023-06-01")
                }
                AiProvider.GEMINI -> connection.setRequestProperty("x-goog-api-key",key)
                else -> connection.setRequestProperty("Authorization","Bearer $key")
            }
            val bytes=payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status=connection.responseCode
            if(status !in 200..299) throw AiNetworkException("Provider returned HTTP $status")
            val text=connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out=StringBuilder()
                val buf=CharArray(2048)
                while(true) {
                    val n=reader.read(buf)
                    if(n<0) break
                    if(out.length+n>131072) throw AiNetworkException("Response too large")
                    out.append(buf,0,n)
                }
                out.toString()
            }
            val json=JSONObject(text)
            return when(provider) {
                AiProvider.OPENAI,AiProvider.DEEPSEEK -> json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                AiProvider.CLAUDE -> textParts(json.getJSONArray("content"))
                AiProvider.GEMINI -> textParts(json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts"))
            }
        } finally { connection.disconnect() }
    }
    private fun textParts(parts:JSONArray):String {
        val out=StringBuilder()
        for(i in 0 until parts.length()) {
            val part=parts.getJSONObject(i)
            if(part.optBoolean("thought",false)) continue
            if(part.has("type") && part.optString("type")!="text") continue
            if(part.has("text")) out.append(part.getString("text"))
        }
        return out.toString()
    }
}
internal class AiNetworkException(message:String):Exception(message)
