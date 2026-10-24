package com.victor.traillink.service

import java.io.InputStream
import java.io.OutputStream
import java.net.*
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Paho connects only to the FIRST address DNS returns. On networks with broken IPv6 (seen on Victor's Wi-Fi:
 * broker.hivemq.com resolves to IPv6 first, SYN to [2a01:..]:8883 never answers) it never connects at all.
 * This factory tries every resolved address, IPv4 first, each with its own timeout, then does TLS with
 * SNI + HTTPS hostname verification. Use it with a "tcp://" URI so Paho does not wrap the socket again.
 */
class DualStackTlsFactory(private val tls: Boolean, private val perAddressTimeoutMs: Int = 8000) : SocketFactory() {
    override fun createSocket(): Socket = DualStackSocket(tls, perAddressTimeoutMs)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }
    override fun createSocket(host: String, port: Int, la: InetAddress?, lp: Int): Socket = createSocket(host, port)
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(a: InetAddress, p: Int, la: InetAddress?, lp: Int): Socket = createSocket(a, p)

    companion object {
        fun orderedAddresses(host: String): List<InetAddress> {
            val all = InetAddress.getAllByName(host).toList()
            return all.filterIsInstance<Inet4Address>() + all.filterNot { it is Inet4Address }
        }
    }
}

private class DualStackSocket(private val tls: Boolean, private val perTimeout: Int) : Socket() {
    @Volatile private var d: Socket? = null
    private var soTimeout = 0
    @Volatile private var closed = false
    var connectedAddress: InetAddress? = null; private set

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val ep = endpoint as InetSocketAddress
        val host = ep.hostString
        val addrs = DualStackTlsFactory.orderedAddresses(host)
        var last: Exception? = null
        for (a in addrs) {
            if (closed) throw SocketException("closed")
            val s = Socket()
            try {
                s.connect(InetSocketAddress(a, ep.port), if (timeout > 0) minOf(timeout, perTimeout) else perTimeout)
                s.tcpNoDelay = true
                val out: Socket = if (tls) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, ep.port, true) as SSLSocket
                    val params = ssl.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    params.serverNames = listOf(SNIHostName(host))
                    ssl.sslParameters = params
                    ssl.soTimeout = 15_000
                    ssl.startHandshake()
                    ssl
                } else s
                out.soTimeout = soTimeout
                connectedAddress = a
                d = out
                return
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
            }
        }
        throw (last as? java.io.IOException) ?: java.io.IOException("no address for $host", last)
    }

    private fun s(): Socket = d ?: throw SocketException("not connected")
    override fun getInputStream(): InputStream = s().getInputStream()
    override fun getOutputStream(): OutputStream = s().getOutputStream()
    override fun close() { closed = true; d?.close() }
    override fun isConnected() = d?.isConnected == true
    override fun isClosed() = closed || d?.isClosed == true
    override fun setSoTimeout(timeout: Int) { soTimeout = timeout; d?.soTimeout = timeout }
    override fun getSoTimeout() = d?.soTimeout ?: soTimeout
    override fun setTcpNoDelay(on: Boolean) { d?.tcpNoDelay = on }
    override fun getTcpNoDelay() = d?.tcpNoDelay ?: true
    override fun setKeepAlive(on: Boolean) { d?.keepAlive = on }
    override fun getInetAddress(): InetAddress? = d?.inetAddress
    override fun getPort() = d?.port ?: 0
    override fun getLocalPort() = d?.localPort ?: -1
    override fun getLocalAddress(): InetAddress? = d?.localAddress
    override fun getRemoteSocketAddress(): SocketAddress? = d?.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = d?.localSocketAddress
    override fun shutdownInput() { d?.shutdownInput() }
    override fun shutdownOutput() { d?.shutdownOutput() }
    override fun isInputShutdown() = d?.isInputShutdown ?: false
    override fun isOutputShutdown() = d?.isOutputShutdown ?: false
    override fun setSoLinger(on: Boolean, linger: Int) { d?.setSoLinger(on, linger) }
    override fun toString() = "DualStackSocket(${d})"
}
