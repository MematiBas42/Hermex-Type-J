package io.github.jcartu.hermex.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CacheFallbackPolicyTest {

    @Test
    fun connectivityFailuresFallBackToCache() {
        assertTrue(CacheFallbackPolicy.shouldUseCache(ApiError.Network(UnknownHostException("host"))))
        assertTrue(CacheFallbackPolicy.shouldUseCache(ApiError.Network(ConnectException("refused"))))
        assertTrue(CacheFallbackPolicy.shouldUseCache(ApiError.Network(SocketTimeoutException("timeout"))))
        // Bare connectivity errors (not wrapped in ApiError) also qualify.
        assertTrue(CacheFallbackPolicy.shouldUseCache(UnknownHostException("host")))
    }

    @Test
    fun transientUnavailableStatusesFallBackToCache() {
        for (code in intArrayOf(408, 502, 503, 504)) {
            assertTrue("HTTP $code should use cache", CacheFallbackPolicy.shouldUseCache(ApiError.Http(code, null)))
        }
    }

    @Test
    fun permanentFailuresNeverFallBackToCache() {
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Unauthorized))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Http(400, null)))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Http(404, null)))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Http(500, null)))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Decoding(IllegalArgumentException("bad"))))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.InvalidServerUrl))
        assertFalse(CacheFallbackPolicy.shouldUseCache(ApiError.Network(IllegalStateException("not connectivity"))))
    }
}
