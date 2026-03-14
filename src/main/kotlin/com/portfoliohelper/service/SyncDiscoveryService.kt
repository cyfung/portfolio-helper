package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceInfo

object SyncDiscoveryService {
    private val logger = LoggerFactory.getLogger(SyncDiscoveryService::class.java)
    private var jmmdns: JmmDNS? = null

    fun start(port: Int) {
        try {
            // Force IPv4 to avoid JmDNS issues on Windows (SocketException: setsockopt)
            System.setProperty("java.net.preferIPv4Stack", "true")

            // Use Multi-interface JmDNS for better reliability on Windows
            jmmdns = JmmDNS.Factory.getInstance()

            val serviceInfo = ServiceInfo.create(
                "_ibviewer._tcp.local.",
                "Portfolio Helper",
                port,
                0, 0,
                mapOf("path" to "/")
            )

            jmmdns?.registerService(serviceInfo)
            logger.info("Registered mDNS service on all interfaces: _ibviewer._tcp.local. on port $port")
        } catch (e: Exception) {
            logger.error("Failed to start mDNS discovery service", e)
        }
    }

    fun stop() {
        try {
            jmmdns?.unregisterAllServices()
            jmmdns?.close()
        } catch (_: Exception) {}
        jmmdns = null
        logger.info("Stopped mDNS discovery service")
    }
}
