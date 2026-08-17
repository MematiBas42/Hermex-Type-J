package io.github.jcartu.hermex.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Port of `Networking/CacheFallbackPolicy.swift`.
 *
 * Decides whether a failed request should fall back to the offline cache:
 * only connectivity failures and transient server-unavailable statuses
 * qualify; auth failures, 4xx rejections, and decode errors must surface.
 */
object CacheFallbackPolicy {

    fun shouldUseCache(error: Throwable): Boolean = when (error) {
        is ApiError.Network -> isConnectivityError(error.underlying)
        is ApiError.Http -> isTransientUnavailableStatus(error.statusCode)
        else -> isConnectivityError(error)
    }

    /**
     * The JVM analogue of the Swift `URLError` connectivity codes
     * (notConnectedToInternet, networkConnectionLost, cannotConnectToHost,
     * dnsLookupFailed, cannotFindHost, dataNotAllowed, timedOut).
     */
    private fun isConnectivityError(error: Throwable): Boolean = when (error) {
        is SocketTimeoutException,
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is SocketException,
        -> true

        else -> false
    }

    private fun isTransientUnavailableStatus(statusCode: Int): Boolean = when (statusCode) {
        408, 502, 503, 504 -> true
        else -> false
    }
}
