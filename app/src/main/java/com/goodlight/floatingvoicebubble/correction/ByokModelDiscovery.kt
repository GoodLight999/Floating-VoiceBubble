package com.goodlight.floatingvoicebubble.correction

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ByokModelInfo(
    val id: String,
    val displayName: String = id,
    val contextLength: Long? = null,
    val supportedParameters: Set<String> = emptySet(),
    val promptPricePerMillion: Double? = null,
    val completionPricePerMillion: Double? = null,
    val description: String = "",
) {
    val supportsReasoning: Boolean get() = "reasoning" in supportedParameters || "reasoning_effort" in supportedParameters
}

class ByokModelDiscovery {
    fun list(endpoint: String, apiKey: String): List<ByokModelInfo> {
        val resolved = ByokEndpointResolver.resolve(endpoint)
        if (resolved.protocol != CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE) {
            require(apiKey.isNotBlank()) { "このAPIのモデル一覧取得にはAPI keyが必要です。" }
        }
        val models = when (resolved.protocol) {
            CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> listOpenAiCompatible(resolved.modelsUrl, apiKey)
            CloudCorrectorFactory.Protocol.ANTHROPIC -> listAnthropic(resolved.modelsUrl, apiKey)
            CloudCorrectorFactory.Protocol.GEMINI -> listGemini(resolved.modelsUrl, apiKey)
        }
        return models.distinctBy { it.id }.sortedBy { it.id.lowercase() }
    }

    private fun listOpenAiCompatible(url: String, apiKey: String): List<ByokModelInfo> {
        val json = getJson(url, CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE, apiKey)
        val data = json.optJSONArray("data") ?: error("モデル一覧レスポンスに data がありません。")
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) continue
                val supported = item.optJSONArray("supported_parameters")?.let { values ->
                    buildSet {
                        for (i in 0 until values.length()) values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }.orEmpty()
                val pricing = item.optJSONObject("pricing")
                add(
                    ByokModelInfo(
                        id = id,
                        displayName = item.optString("name").ifBlank {
                            item.optString("display_name", id).ifBlank { id }
                        },
                        contextLength = item.optLong("context_length").takeIf { it > 0L },
                        supportedParameters = supported,
                        promptPricePerMillion = pricing?.optString("prompt")?.toDoubleOrNull()?.times(1_000_000.0),
                        completionPricePerMillion = pricing?.optString("completion")?.toDoubleOrNull()?.times(1_000_000.0),
                        description = item.optString("description").trim(),
                    ),
                )
            }
        }
    }

    private fun listAnthropic(baseUrl: String, apiKey: String): List<ByokModelInfo> {
        val result = mutableListOf<ByokModelInfo>()
        var afterId: String? = null
        repeat(MAX_PAGES) {
            val url = buildString {
                append(baseUrl)
                append(if ('?' in baseUrl) '&' else '?')
                append("limit=100")
                afterId?.let { append("&after_id=").append(encode(it)) }
            }
            val json = getJson(url, CloudCorrectorFactory.Protocol.ANTHROPIC, apiKey)
            val data = json.optJSONArray("data") ?: error("Anthropicモデル一覧レスポンスに data がありません。")
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isNotBlank()) result += ByokModelInfo(id, item.optString("display_name", id).ifBlank { id })
            }
            if (!json.optBoolean("has_more", false)) return result
            val next = json.optString("last_id").trim()
            if (next.isBlank() || next == afterId) error("Anthropicモデル一覧のページ送りが進みませんでした。")
            afterId = next
        }
        error("Anthropicモデル一覧が${MAX_PAGES}ページを超えました。")
    }

    private fun listGemini(baseUrl: String, apiKey: String): List<ByokModelInfo> {
        val result = mutableListOf<ByokModelInfo>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = buildString {
                append(baseUrl)
                append(if ('?' in baseUrl) '&' else '?')
                append("pageSize=1000")
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val json = getJson(url, CloudCorrectorFactory.Protocol.GEMINI, apiKey)
            val data = json.optJSONArray("models") ?: error("Geminiモデル一覧レスポンスに models がありません。")
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
                result += ByokModelInfo(
                    id = id,
                    displayName = item.optString("displayName", id).ifBlank { id },
                    contextLength = item.optLong("inputTokenLimit").takeIf { it > 0L },
                    description = item.optString("description").trim(),
                )
            }
            val next = json.optString("nextPageToken").trim()
            if (next.isBlank()) return result
            if (next == pageToken) error("Geminiモデル一覧のページ送りが進みませんでした。")
            pageToken = next
        }
        error("Geminiモデル一覧が${MAX_PAGES}ページを超えました。")
    }

    private fun getJson(url: String, protocol: CloudCorrectorFactory.Protocol, apiKey: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            when (protocol) {
                CloudCorrectorFactory.Protocol.OPENAI_COMPATIBLE -> {
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
                }
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
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private fun compact(value: String): String = value.take(500).replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val MAX_PAGES = 100
    }
}