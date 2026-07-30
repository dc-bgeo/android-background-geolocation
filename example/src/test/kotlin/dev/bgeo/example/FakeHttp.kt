package dev.bgeo.example

/**
 * Scripted [Http] fake: enqueue responses in the order they should be
 * returned, and inspect [requests] afterwards. `send` fails loudly (rather
 * than looping or returning a default) once the script runs dry, so a test
 * that asserts the wrong request count fails clearly instead of silently
 * reusing a stale response.
 */
class FakeHttp : Http {
    private val responses = ArrayDeque<HttpResponse>()
    val requests = mutableListOf<HttpRequest>()

    fun enqueue(response: HttpResponse) {
        responses.addLast(response)
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests.add(request)
        check(responses.isNotEmpty()) {
            "FakeHttp: no response queued for request #${requests.size} (${request.method} ${request.url})"
        }
        return responses.removeFirst()
    }
}

/** In-memory [Storage] fake — the real implementation wraps `SharedPreferences`, which is stubbed in unit tests. */
class InMemoryStorage : Storage {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
