package dev.bgeo.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    /** Redacted: headers carry the bearer token and the body can carry the refresh token; both are logged verbatim by Task 7's log uploader otherwise. */
    override fun toString(): String {
        val redactedHeaders = headers.mapValues { (key, value) ->
            if (key.equals("Authorization", ignoreCase = true)) "<redacted>" else value
        }
        return "HttpRequest(method=$method, url=$url, headers=$redactedHeaders, body=${body?.let { "<redacted>" }})"
    }
}

data class HttpResponse(val status: Int, val body: String)

/**
 * A one-method seam over the network so [DeviceLink] is unit-testable
 * without a real network stack — with `isReturnDefaultValues = true` there is
 * no way to exercise `HttpURLConnection` on the JVM test runner, and adding
 * MockWebServer would be a dependency this dev tool does not need.
 */
interface Http {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Production [Http] over `java.net.HttpURLConnection`. No new dependency. */
class HttpUrlConnectionHttp : Http {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = request.method
            connection.doInput = true
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpResponse(status, body)
        } catch (e: IOException) {
            // A typo'd server URL is the single most likely user error on the
            // link screen; surface it as DeviceLinkError, not a raw
            // IOException/UnknownHostException, so callers catching
            // DeviceLinkError (the type this app exports for exactly this)
            // actually catch it.
            throw DeviceLinkError("network request failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        // iOS gets URLSession's 60s default connect/read timeout for free;
        // HttpURLConnection defaults both to 0 (infinite), which would hang
        // the coroutine (and the link spinner) against a blackholed host.
        private const val TIMEOUT_MS = 60_000
    }
}
