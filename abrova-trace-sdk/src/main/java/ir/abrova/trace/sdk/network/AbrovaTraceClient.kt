package ir.abrova.trace.sdk.network

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import ir.abrova.trace.sdk.AbrovaTraceConfig
import ir.abrova.trace.sdk.models.*
import ir.abrova.trace.sdk.utils.AbrovaTraceLogger
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for AbrovaTrace API
 */
class AbrovaTraceClient(
    private val config: AbrovaTraceConfig
) {
    companion object {
        private val JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8")
        private const val API_KEY_HEADER = "X-API-Key"
    }

    private val baseUrl: String = config.apiUrl.trimEnd('/')

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .readTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .writeTimeout(config.httpTimeout.toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Send an error to AbrovaTrace
     */
    fun sendError(error: AbrovaTraceError, callback: ((Boolean, String?) -> Unit)? = null) {
        val url = "$baseUrl/api/errors"
        val json = gson.toJson(error.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending error to AbrovaTrace: ${error.message}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send error: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Error sent successfully")
                } else {
                    AbrovaTraceLogger.error("Error response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Send an error synchronously (for use in crash handlers)
     */
    fun sendErrorSync(error: AbrovaTraceError): Boolean {
        val url = "$baseUrl/api/errors"
        val json = gson.toJson(error.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        return try {
            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (!success) {
                AbrovaTraceLogger.error("sendErrorSync HTTP ${response.code()} url=$url")
            }
            response.close()
            success
        } catch (e: Exception) {
            AbrovaTraceLogger.error(
                "sendErrorSync ${e.javaClass.simpleName}: ${e.message ?: "no message"} url=$url"
            )
            false
        }
    }

    /**
     * Send a message to AbrovaTrace
     */
    fun sendMessage(message: AbrovaTraceError, callback: ((Boolean, String?) -> Unit)? = null) {
        val url = "$baseUrl/api/messages"
        val json = gson.toJson(message.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending message to AbrovaTrace: ${message.message}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send message: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Message sent successfully")
                } else {
                    AbrovaTraceLogger.error("Message response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Send a performance span to AbrovaTrace APM
     */
    fun sendPerformanceSpan(span: PerformanceSpan, callback: ((Boolean, String?) -> Unit)? = null) {
        val url = "$baseUrl/api/performance/spans"
        val json = gson.toJson(span.toMap())
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending performance span: ${span.operation}")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send span: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Span sent successfully")
                } else {
                    AbrovaTraceLogger.error("Span response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Send multiple performance spans in a batch
     */
    fun sendPerformanceSpanBatch(spans: List<PerformanceSpan>, callback: ((Boolean, String?) -> Unit)? = null) {
        if (spans.isEmpty()) {
            callback?.invoke(true, null)
            return
        }

        val url = "$baseUrl/api/performance/spans/batch"
        val json = gson.toJson(mapOf("spans" to spans.map { it.toMap() }))
        val body = RequestBody.create(JSON_MEDIA_TYPE, json)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader(API_KEY_HEADER, config.apiKey)
            .addHeader("User-Agent", "AbrovaTrace-SDK/${ir.abrova.trace.sdk.BuildConfig.SDK_VERSION} (android)")
            .build()

        AbrovaTraceLogger.debug("Sending ${spans.size} performance spans")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AbrovaTraceLogger.error("Failed to send span batch: ${e.message}")
                callback?.invoke(false, e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                val success = response.isSuccessful
                val responseBody = response.body()?.string()
                response.close()

                if (success) {
                    AbrovaTraceLogger.debug("Span batch sent successfully")
                } else {
                    AbrovaTraceLogger.error("Span batch response: $responseBody")
                }

                callback?.invoke(success, responseBody)
            }
        })
    }

    /**
     * Shutdown the client
     */
    fun shutdown() {
        client.dispatcher().executorService().shutdown()
        client.connectionPool().evictAll()
    }
}
