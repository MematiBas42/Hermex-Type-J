package io.github.jcartu.hermex.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Port of `Networking/CustomHeader.swift`.
 *
 * A single user-supplied HTTP request header attached to every outgoing
 * same-origin request. This exists so self-hosters behind an authenticated
 * reverse proxy (e.g. Authentik) or a token-gated WebUI can reach a deployment
 * the password-only flow can't (issue #255). The value may be a secret, so
 * callers persist the list encrypted (see `core/crypto`).
 *
 * Decoding is tolerant: a malformed/missing field decodes to empty rather than
 * failing, so one bad entry can't wipe the whole stored list.
 */
@Serializable
data class CustomHeader(
    val name: String = "",
    val value: String = "",
) {
    /** Leading/trailing whitespace trimmed; internal spaces (e.g. `Bearer <token>`) preserved. */
    val sanitizedName: String get() = name.trim()
    val sanitizedValue: String get() = value.trim()

    /**
     * A header is usable when its name is a valid RFC 7230 token (non-empty,
     * only token characters — so no spaces or colons) and its value contains
     * no newline (which would allow HTTP header injection). Empty or malformed
     * rows — e.g. one the user is still typing — are simply skipped.
     */
    val isApplicable: Boolean
        get() {
            val name = sanitizedName
            if (name.isEmpty()) return false
            if (!name.all { it in HEADER_NAME_ALLOWED }) return false
            return '\n' !in value && '\r' !in value
        }

    companion object {
        /** RFC 7230 `token` characters allowed in a header field name. */
        private val HEADER_NAME_ALLOWED: Set<Char> = buildSet {
            addAll("!#$%&'*+-.^_`|~".toList())
            addAll('0'..'9')
            addAll('a'..'z')
            addAll('A'..'Z')
        }

        private val storageJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        /**
         * Decodes a stored JSON string back into headers. Any failure (null,
         * garbage, schema drift) yields an empty list rather than throwing.
         */
        fun decodeFromStorage(string: String?): List<CustomHeader> {
            if (string.isNullOrEmpty()) return emptyList()
            return runCatching { storageJson.decodeFromString<List<CustomHeader>>(string) }
                .getOrDefault(emptyList())
        }
    }
}

/**
 * Sets each applicable header on the request builder. The caller sets built-in
 * headers (`Accept`/`Content-Type`) *after* calling this so built-ins always
 * win those keys; any other header (`Authorization`, `X-Api-Key`, …) passes
 * through. An empty list is a true no-op.
 */
fun List<CustomHeader>.applyTo(builder: Request.Builder) {
    for (header in this) {
        if (header.isApplicable) {
            builder.header(header.sanitizedName, header.sanitizedValue)
        }
    }
}

/**
 * Merges these headers underneath [builtIns] into a single map so built-ins
 * win on collision. Used for the SSE stream request.
 */
fun List<CustomHeader>.mergedUnder(builtIns: Map<String, String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (header in this) {
        if (header.isApplicable) {
            result[header.sanitizedName] = header.sanitizedValue
        }
    }
    result.putAll(builtIns)
    return result
}

/**
 * Drops rows with no usable name (blank/whitespace-only) so half-typed
 * placeholder rows aren't stored or applied; the rest are kept verbatim.
 */
fun List<CustomHeader>.sanitizedForStorage(): List<CustomHeader> =
    filter { it.sanitizedName.isNotEmpty() }

/**
 * JSON string (`[{"name":…,"value":…}]`) for encrypted storage, or null if the
 * list is empty.
 */
fun List<CustomHeader>.encodedForStorage(): String? {
    if (isEmpty()) return null
    return runCatching {
        Json.encodeToString(ListSerializer(CustomHeader.serializer()), this)
    }.getOrNull()
}

/**
 * Process-wide, thread-safe snapshot of the user's custom headers.
 * `HermesApi`/`SseChatStream` read the current headers when building each
 * request (at request time, so even long-lived clients pick up an edit
 * immediately). The settings feature owns all writes.
 */
class CustomHeaderStore(headers: List<CustomHeader> = emptyList()) {

    @Volatile
    private var headers: List<CustomHeader> = headers

    fun snapshot(): List<CustomHeader> = headers

    fun replace(headers: List<CustomHeader>) {
        this.headers = headers
    }

    companion object {
        val shared = CustomHeaderStore()
    }
}

/**
 * Port of `CrossOriginHeaderStripper` (Swift `URLSession` redirect delegate,
 * issue #277), expressed as an OkHttp *network* interceptor — network
 * interceptors run once per hop, including redirect follow-ups, so they see
 * the exact request OkHttp is about to send to the new host.
 *
 * OkHttp already strips `Authorization` when a redirect changes the host, so
 * the realistic residual leak is a non-`Authorization` custom header (e.g.
 * `X-Api-Key`) — which may be a secret. When the hop's destination is not the
 * configured server origin, every configured custom header is removed by name
 * (case-insensitively, since HTTP field names are case-insensitive).
 * Same-origin hops keep the headers (a proxy path rewrite still needs them);
 * a request with no custom headers passes through untouched.
 */
class CrossOriginHeaderStripper(
    private val baseUrlProvider: () -> HttpUrl?,
    private val customHeaderProvider: () -> List<CustomHeader>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val baseUrl = baseUrlProvider() ?: return chain.proceed(request)
        if (isSameOrigin(request.url, baseUrl)) return chain.proceed(request)

        val namesToStrip = customHeaderProvider()
            .filter { it.isApplicable }
            .map { it.sanitizedName.lowercase() }
            .toSet()
        if (namesToStrip.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        for (name in request.headers.names()) {
            if (name.lowercase() in namesToStrip) {
                builder.removeHeader(name)
            }
        }
        return chain.proceed(builder.build())
    }

    companion object {
        /** Scheme + host + effective port comparison, mirroring `APIClient.isSameOrigin`. */
        fun isSameOrigin(url: HttpUrl, baseUrl: HttpUrl): Boolean =
            url.scheme.equals(baseUrl.scheme, ignoreCase = true) &&
                url.host.equals(baseUrl.host, ignoreCase = true) &&
                url.port == baseUrl.port
    }
}
