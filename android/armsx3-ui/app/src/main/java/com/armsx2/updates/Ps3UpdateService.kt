package com.armsx2.updates

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Title updates, straight from Sony's own update service.
 *
 * A PS3 asks `<TITLEID>-ver.xml` for the list of patches published for a disc, and that endpoint is
 * still live and still unauthenticated. Issue #54: the usual fix for a game that boots to a black
 * screen is its day-one patch, and until now the only way to get one was a desktop tool.
 */
object Ps3UpdateService {

    private const val TAG = "Ps3Update"
    private const val VER_XML = "https://a0.ww.np.dl.playstation.net/tpl/np/%1\$s/%1\$s-ver.xml"

    /**
     * Hosts whose certificate we do not validate, matched on suffix so a lookalike domain cannot
     * opt itself in.
     *
     * The update service presents a chain that does not terminate in any root Android ships -- a
     * PS3 validates it against Sony's own store, which we do not have -- so a stock HttpsURLConnection
     * cannot complete the handshake at all. Every desktop tool that talks to this endpoint relaxes
     * verification for it.
     *
     * Relaxed ONLY for these hosts. The socket factory below is attached per connection, never
     * installed as the default, so every other request the app makes is verified normally.
     *
     * What that costs, stated plainly: whoever controls this connection chooses the response, and
     * the SHA-1 in [Ps3Update.sha1] cannot defend against that because it arrives over the same
     * connection. It defends against a corrupted or truncated download, which is what it is for.
     * The package is still only unpacked into the emulator's own storage.
     */
    private val RELAXED_HOSTS = listOf(".np.dl.playstation.net", ".dl.playstation.net")

    private fun isRelaxedHost(host: String?): Boolean {
        val value = host.orEmpty().lowercase()
        return RELAXED_HOSTS.any { value.endsWith(it) }
    }

    private val relaxedFactory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }.socketFactory
    }

    /**
     * Upgrade a PlayStation URL to https.
     *
     * The package urls inside ver.xml are plain http, and Android has refused cleartext by default
     * since API 28 -- the download failed with "Cleartext HTTP traffic not permitted" before a byte
     * moved. The obvious fix is a cleartext exception for the domain in network_security_config,
     * but it is not needed: these hosts serve the same object over https. Rewriting the scheme
     * keeps the app's no-cleartext-anywhere posture intact and encrypts the transfer, and the
     * connection then takes the same relaxed-verification path as the metadata lookup, since the
     * certificate does not validate on either host.
     *
     * Only for hosts we already relax, so this can never silently upgrade somewhere else and fail.
     */
    private fun preferHttps(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        val host = runCatching { URL(url).host }.getOrNull()
        return if (isRelaxedHost(host)) "https://" + url.substring("http://".length) else url
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(preferHttps(url)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ARMSX3")

        if (connection is HttpsURLConnection && isRelaxedHost(connection.url.host)) {
            connection.sslSocketFactory = relaxedFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        return connection
    }

    /**
     * SHA-1 over everything except the last [TRAILER] bytes of the stream.
     *
     * Written as a delay line rather than by seeking: the input is a socket, the file is too big to
     * revisit cheaply, and the only thing needed is to never feed the digest bytes that might turn
     * out to be the trailer. Bytes leave the buffer and enter the digest once enough have arrived
     * after them to prove they are not the tail.
     */
    private class TrailingSha1 {
        private val digest = MessageDigest.getInstance("SHA-1")
        private val tail = ByteArray(TRAILER)
        private var held = 0

        fun update(source: ByteArray, offset: Int, length: Int) {
            var pos = offset
            var left = length

            // Flush whatever the held bytes can no longer be the tail of.
            if (held > 0) {
                val canFlush = minOf(held, maxOf(0, held + left - TRAILER))
                if (canFlush > 0) {
                    digest.update(tail, 0, canFlush)
                    System.arraycopy(tail, canFlush, tail, 0, held - canFlush)
                    held -= canFlush
                }
            }

            // Everything past the final TRAILER bytes of this chunk is safe to digest now.
            val keep = minOf(left, TRAILER - held)
            val direct = left - keep
            if (direct > 0) {
                digest.update(source, pos, direct)
                pos += direct
                left -= direct
            }

            System.arraycopy(source, pos, tail, held, left)
            held += left
        }

        fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }

        companion object {
            /** The .pkg trailer the published sha1sum is taken without. */
            const val TRAILER = 0x20
        }
    }

    /** One published patch. [url] is the .pkg; [version] is APP_VER, so it compares to the info tab. */
    data class Ps3Update(
        val titleId: String,
        val version: String,
        val sizeBytes: Long,
        val sha1: String,
        val url: String,
        val systemVersion: String,
        val title: String,
    )

    sealed interface Lookup {
        data class Found(val updates: List<Ps3Update>) : Lookup
        /** The service answered and has nothing. A 404 here means "no patches", not a failure. */
        data object None : Lookup
        data class Failed(val reason: String) : Lookup
    }

    /**
     * Ask the service what exists for [titleId].
     *
     * Results come back in publication order, which is the order a PS3 would apply them; the last
     * entry is the newest. Sony publishes cumulative packages, so installing only the last is
     * normally right, but the whole list is returned rather than assumed.
     */
    suspend fun find(titleId: String): Lookup = withContext(Dispatchers.IO) {
        val id = titleId.trim().uppercase()

        if (id.isEmpty() || !id.all { it.isLetterOrDigit() }) {
            return@withContext Lookup.Failed("'$titleId' is not a title id")
        }

        runCatching {
            val connection = open(VER_XML.format(id))

            try {
                when (val code = connection.responseCode) {
                    200 -> Unit
                    // Both mean the same thing here: nothing has ever been published for this
                    // title. Most discs are in that group and it is not an error worth showing.
                    404, 403 -> return@withContext Lookup.None
                    else -> return@withContext Lookup.Failed("update service returned HTTP $code")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }

                if (body.isBlank()) {
                    return@withContext Lookup.None
                }

                val updates = parse(id, body)
                if (updates.isEmpty()) Lookup.None else Lookup.Found(updates)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            Lookup.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    /**
     * Pull the <package> elements out of a titlepatch document.
     *
     * Written against the document rather than a schema: attribute order and the surrounding <tag>
     * grouping vary between titles, and elements other than <package> carry no attributes we use.
     * A <paramsfo><TITLE> child gives the game's name, which is the only way to label a result for
     * a title the user does not own yet.
     */
    private fun parse(titleId: String, xml: String): List<Ps3Update> {
        val out = mutableListOf<Ps3Update>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }

        var pending: Ps3Update? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "package" -> {
                        val url = parser.getAttributeValue(null, "url").orEmpty()
                        val version = parser.getAttributeValue(null, "version").orEmpty()

                        if (url.isNotBlank() && version.isNotBlank()) {
                            pending = Ps3Update(
                                titleId = titleId,
                                version = version,
                                sizeBytes = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L,
                                sha1 = parser.getAttributeValue(null, "sha1sum").orEmpty(),
                                url = url,
                                systemVersion = parser.getAttributeValue(null, "ps3_system_ver").orEmpty(),
                                title = "",
                            )
                        }
                    }
                    // Inside <paramsfo>, which is inside the <package> we just opened.
                    "TITLE" -> pending?.let { p ->
                        val text = parser.nextText().orEmpty().trim()
                        if (text.isNotBlank()) pending = p.copy(title = text)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "package") {
                    pending?.let(out::add)
                    pending = null
                }
            }
        }

        return out
    }

    /**
     * Download [update] to [dest], reporting progress as a 0..1 fraction.
     *
     * The SHA-1 is computed while the bytes stream past rather than by re-reading afterwards: these
     * packages run to several gigabytes and a second pass over one costs real time on a phone.
     * A mismatch deletes the file -- handing a bad package to the installer produces a
     * half-installed title, which is worse than no title.
     *
     * The digest deliberately EXCLUDES the final 32 bytes. A .pkg carries its own SHA-1 in a
     * trailer, and the sha1sum the service publishes is taken over the file without it, so hashing
     * the whole download never matches and would reject every package. [TrailingSha1] keeps the
     * last 32 bytes back as it goes.
     */
    suspend fun download(
        update: Ps3Update,
        dest: File,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(update.url)

            try {
                if (connection.responseCode != 200) {
                    error("download returned HTTP ${connection.responseCode}")
                }

                val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.sizeBytes
                val digest = TrailingSha1()
                var read = 0L
                var lastReported = -1f

                dest.parentFile?.mkdirs()
                connection.inputStream.use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n

                            if (total > 0) {
                                val fraction = (read.toFloat() / total).coerceIn(0f, 1f)
                                // Only on a visible change: this loop runs thousands of times on a
                                // multi-gigabyte package and every call crosses to the UI thread.
                                if (fraction - lastReported >= 0.005f) {
                                    lastReported = fraction
                                    onProgress(fraction)
                                }
                            }
                        }
                    }
                }

                if (total > 0 && read != total) {
                    dest.delete()
                    error("download is $read bytes, expected $total")
                }

                // Only when the service published one. A few titles carry an empty sha1sum, and
                // refusing those would block a package that is otherwise fine.
                val expected = update.sha1.trim().lowercase()
                if (expected.isNotEmpty()) {
                    val actual = digest.hex()
                    if (actual != expected) {
                        dest.delete()
                        error("SHA-1 mismatch: got $actual, expected $expected")
                    }
                }

                onProgress(1f)
                dest
            } finally {
                connection.disconnect()
            }
        }.onFailure { dest.delete() }
    }
}
