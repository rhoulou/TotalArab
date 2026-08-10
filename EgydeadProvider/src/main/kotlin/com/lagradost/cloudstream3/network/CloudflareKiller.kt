package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Compile-time stand-in for the host app's CloudflareKiller.
 *
 * The recode library jar does not ship this class (it lives in the host app
 * APK), so plugins cannot compile against it. The host app loads plugin dex
 * files parent-first, meaning at runtime the app's real CloudflareKiller is
 * always resolved when present and this copy is inert. To stay useful even
 * when the host lacks it, this copy solves challenges through the library's
 * own WebViewResolver and remembers the cookies it learns per host.
 */
class CloudflareKiller : Interceptor {

    private companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    /** Host -> cookie name/value pairs learned from previous challenges. */
    val savedCookies = mutableMapOf<String, MutableMap<String, String>>()

    var userAgent: String
        get() = WebViewResolver.webViewUserAgent ?: DEFAULT_USER_AGENT
        set(value) {
            WebViewResolver.webViewUserAgent = value
        }

    private val resolver = WebViewResolver(
        interceptUrl = Regex("^https?://.*"),
        additionalUrls = emptyList(),
        userAgent = userAgent,
        useOkhttp = false,
        script = "",
        scriptCallback = { },
        timeout = WebViewResolver.DEFAULT_TIMEOUT
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        savedCookies[host]?.takeIf { it.isNotEmpty() }?.let { cookies ->
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val built = request.newBuilder()
                .header("User-Agent", userAgent)
                .header("Cookie", cookieHeader)
                .build()
            return chain.proceed(built)
        }

        val response = chain.proceed(request)
        if (response.code == 403 || response.code == 503 || response.code == 429) {
            response.close()
            val solved = resolver.intercept(chain)
            runCatching { rememberCookies(host, solved) }
            return solved
        }
        return response
    }

    private fun rememberCookies(host: String, response: Response) {
        response.request.header("Cookie")?.let { cookieHeader ->
            val parsed = cookieHeader.split(";").mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) part.substring(0, index).trim() to part.substring(index + 1).trim() else null
            }.toMap()
            if (parsed.isNotEmpty()) {
                savedCookies[host] = parsed.toMutableMap()
            }
        }
        response.request.header("User-Agent")?.let { WebViewResolver.webViewUserAgent = it }
    }

    fun getCookieHeaders(url: String): Headers {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return Headers.Builder().build()
        val builder = Headers.Builder()
        savedCookies[host]?.forEach { (key, value) ->
            builder.add("Cookie", "$key=$value")
        }
        return builder.build()
    }
}
