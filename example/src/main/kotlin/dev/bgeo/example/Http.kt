package dev.bgeo.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

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
        } finally {
            connection.disconnect()
        }
    }
}
