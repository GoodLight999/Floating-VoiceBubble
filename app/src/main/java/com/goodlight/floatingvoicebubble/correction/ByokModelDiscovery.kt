package com.goodlight.floatingvoicebubble.correction

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ByokModelInfo(
    val id: String,
    val displayName: String = id,
)

class ByokModelDiscovery {
    fun list(endpoint: String, apiKey: String): List<ByokModelInfo> {
        val resolved = ByokEndpointResolver.resolve(endpoint)
        require(apiKey.isNotBlank()) { "API key is not configured" }
        val target = when (resolved.protocol) {
            CloudCorrectorFactory.Protocol.GEMINI -> appendQuery(resolved.modelsUrl, "pageSize=1000")
            CloudCorrectorFactory.Protocol.ANTHROPIC -> appendQuery(resolved.modelsUrl, "limit=1000")
            CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> resolved.modelsUrl
        }
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            when (resolved.protocol) {
                CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> setRequestProperty("Authorization", "Bearer $apiKey")
                CloudCorrectorFactory.Protocol.ANTHROPIC -> {
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                CloudCorrectorFactory.Protocol.GEMINI -> setRequestProperty("x-goog-api-key", apiKey)
            }
        }
        try {
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("モデル一覧の取得に失敗しました: HTTP $status ${compact(responseText)}")
            }
            val json = JSONObject(responseText)
            val models = when (resolved.protocol) {
                CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE,
                CloudCorrectorFactory.Protocol.ANTHROPIC -> {
                    val data = json.optJSONArray("data") ?: error("モデル一覧レスポンスに data がありません")
                    buildList {
                        for (index in 0 until data.length()) {
                            val item = data.optJSONObject(index) ?: continue
                            val id = item.optString("id").trim()
                            if (id.isBlank()) continue
                            add(ByokModelInfo(id, item.optString("display_name", id).ifBlank { id }))
                        }
                    }
                }
                CloudCorrectorFactory.Protocol.GEMINI -> {
                    val data = json.optJSONArray("models") ?: error("Geminiモデル一覧レスポンスに models がありません")
                    buildList {
                        for (index in 0 until data.length()) {
                            val item = data.optJSONObject(index) ?: continue
                            val methods = item.optJSONArray("supportedGenerationMethods")
                            val canGenerate = methods == null || (0 until methods.length()).any {
                                methods.optString(it) == "generateContent"
                            }
                            if (!canGenerate) continue
                            val id = item.optString("baseModelId").ifBlank {
                                item.optString("name").removePrefix("models/")
                            }.trim()
                            if (id.isBlank()) continue
                            add(ByokModelInfo(id, item.optString("displayName", id).ifBlank { id }))
                        }
                    }
                }
            }
            return models.distinctBy { it.id }.sortedBy { it.id.lowercase() }
        } finally {
            connection.disconnect()
        }
    }

    private fun appendQuery(url: String, query: String): String =
        if ('?' in url) "$url&$query" else "$url?$query"

    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()
}
