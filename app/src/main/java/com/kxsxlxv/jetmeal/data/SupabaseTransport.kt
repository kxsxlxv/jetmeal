package com.kxsxlxv.jetmeal.data

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Interceptor

/**
 * Prefer IPv4 on dual-stack cellular networks without disabling IPv6-only/NAT64
 * networks. Keep the system resolver and all resolved addresses; OkHttp may still
 * fall back to another route if connecting to the first address fails.
 */
internal fun ipv4First(addresses: List<InetAddress>): List<InetAddress> =
    addresses.filterIsInstance<Inet4Address>() + addresses.filterNot { it is Inet4Address }

internal val supabaseDns = Dns { hostname ->
    ipv4First(Dns.SYSTEM.lookup(hostname))
}

/**
 * Jetmeal had repeated successful headers followed by 25-second response body
 * stalls on both HTTP/2 and HTTP/1.1, including previously pooled H1 sockets.
 * Request a fresh socket and a definitive response end on this H1 transport.
 * Keep this on the network interceptor path (never log headers or bodies).
 */
internal val closeAfterResponse = Interceptor { chain ->
    chain.proceed(chain.request().newBuilder().header("Connection", "close").build())
}
