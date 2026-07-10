package dev.alfaoz.lbt.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque

internal val marketLogger: Logger = LoggerFactory.getLogger("lbt/market")

/** Shared HTTP plumbing: one client, one sliding-window rate limiter across all market calls. */
internal object Http {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Coflnet public tier allows 30 req/10s; everything shares this window to stay far under it.
    private const val MAX_REQUESTS_PER_WINDOW = 20
    private val WINDOW_MILLIS = Duration.ofSeconds(10).toMillis()
    private val limiterMutex = Mutex()
    private val recentRequests = ArrayDeque<Long>()

    suspend fun getString(url: String, timeoutSeconds: Long = 15): String? {
        awaitRateLimit()
        return withContext(Dispatchers.IO) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", "lbt/0.2 (alfaoz.dev)")
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) response.body()
                else {
                    marketLogger.warn("GET $url -> ${response.statusCode()}")
                    null
                }
            } catch (e: Exception) {
                marketLogger.warn("GET $url failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun awaitRateLimit() {
        limiterMutex.withLock {
            val now = System.currentTimeMillis()
            while (recentRequests.isNotEmpty() && now - recentRequests.peekFirst() > WINDOW_MILLIS) {
                recentRequests.pollFirst()
            }
            if (recentRequests.size >= MAX_REQUESTS_PER_WINDOW) {
                val waitMillis = WINDOW_MILLIS - (now - recentRequests.peekFirst())
                if (waitMillis > 0) delay(waitMillis)
            }
            recentRequests.addLast(System.currentTimeMillis())
        }
    }
}
