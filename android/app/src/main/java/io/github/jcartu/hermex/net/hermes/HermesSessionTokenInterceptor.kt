package io.github.jcartu.hermex.net.hermes

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Attaches the `X-Hermes-Session-Token` header to requests going to a Hermes dashboard.
 * If no token is cached in [HermesSessionTokenStore] or if the server returns 401 Unauthorized,
 * this interceptor attempts to scrape the page token from `GET /` on the same origin (the standard
 * open-dashboard session token injection flow) and retries the request once.
 */
class HermesSessionTokenInterceptor(
    private val tokenStore: HermesSessionTokenStore = HermesSessionTokenStore,
    private val rawClientProvider: () -> OkHttpClient,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        val existingSessionToken = request.header(HermesHeaders.SESSION_TOKEN_NAME)
        val hasAuth = request.header("Authorization") != null

        val currentToken = existingSessionToken ?: tokenStore.get(url)

        val requestToSend = if (currentToken != null && existingSessionToken == null) {
            request.newBuilder()
                .header(HermesHeaders.SESSION_TOKEN_NAME, currentToken)
                .build()
        } else {
            request
        }

        val response = chain.proceed(requestToSend)

        // If response is 401 Unauthorized and not an explicit custom Authorization header,
        // scrape page token from GET / and retry once.
        if (response.code == 401 && !hasAuth) {
            val freshToken = scrapePageToken(url)
            if (!freshToken.isNullOrBlank() && freshToken != currentToken) {
                tokenStore.set(url, freshToken)
                response.close()
                val retriedRequest = request.newBuilder()
                    .header(HermesHeaders.SESSION_TOKEN_NAME, freshToken)
                    .build()
                return chain.proceed(retriedRequest)
            }
        }

        return response
    }

    private fun scrapePageToken(url: HttpUrl): String? {
        return try {
            val rootUrl = url.newBuilder().encodedPath("/").query(null).build()
            val getPage = Request.Builder().url(rootUrl).get().build()
            rawClientProvider().newCall(getPage).execute().use { pageResp ->
                if (!pageResp.isSuccessful) return null
                val bodyBytes = pageResp.body?.bytes() ?: return null
                HermesREST.pageToken(bodyBytes)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
