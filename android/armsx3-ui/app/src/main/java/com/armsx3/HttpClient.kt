package com.armsx3

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Minimal synchronous HTTP used by the asset repositories (skins, shaders,
 * drivers, covers).
 *
 * ARMSX2's equivalent lives in `kr.co.iefriends.pcsx2` -- a THIRD-PARTY package
 * with a different rights holder -- and is built on HttpURLConnection. This is a
 * fresh implementation over OkHttp (already a dependency here, and what
 * utils/GitHub.kt uses), written to the same call shape so the ported repos need
 * no edits beyond their package line.
 *
 * Callers check [Response.statusCode]; negative values are transport-level
 * failures rather than HTTP statuses:
 *   -1 generic error, -2 timeout, -3 cancelled.
 */
object HttpClient {

    class Response {
        @JvmField var statusCode: Int = -1
        @JvmField var contentType: String = ""
        @JvmField var data: ByteArray = ByteArray(0)
    }

    const val ERROR_GENERIC = -1
    const val ERROR_TIMEOUT = -2
    const val ERROR_CANCELLED = -3

    // One shared client: OkHttp pools connections and threads, and building a
    // new one per request leaks both. Per-request timeouts are applied below.
    private val base = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @JvmStatic
    @JvmOverloads
    fun doRequest(
        url: String,
        method: String = "GET",
        postData: ByteArray? = null,
        userAgent: String? = null,
        timeoutMs: Int = 20_000,
    ): Response {
        val out = Response()

        val client = base.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder().url(url)
        if (!userAgent.isNullOrEmpty()) {
            builder.header("User-Agent", userAgent)
        }

        if (method.equals("POST", ignoreCase = true)) {
            // Form-encoded to match what the repositories already produce; they
            // hand us a pre-encoded body.
            val body = (postData ?: ByteArray(0))
                .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
            builder.post(body)
        } else {
            builder.get()
        }

        return try {
            client.newCall(builder.build()).execute().use { response ->
                out.statusCode = response.code
                out.contentType = response.header("Content-Type").orEmpty()
                out.data = response.body?.bytes() ?: ByteArray(0)
                out
            }
        } catch (_: SocketTimeoutException) {
            out.statusCode = ERROR_TIMEOUT
            out
        } catch (_: InterruptedIOException) {
            // OkHttp raises this on cancel as well as on some timeouts; the
            // timeout case is caught above, so treat the rest as cancellation.
            out.statusCode = ERROR_CANCELLED
            out
        } catch (_: Exception) {
            out.statusCode = ERROR_GENERIC
            out
        }
    }
}
