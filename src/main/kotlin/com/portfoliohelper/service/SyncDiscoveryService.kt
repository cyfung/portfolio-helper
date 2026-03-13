package com.portfoliohelper.service

import org.slf4j.LoggerFactory
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

object SyncDiscoveryService {
    private val logger = LoggerFactory.getLogger(SyncDiscoveryService::class.java)
    private var jmdns: JmDNS? = null

    fun start(port: Int) {
        try {
            // Force IPv4 to avoid JmDNS issues on Windows (SocketException: setsockopt at setInterface6)
            System.setProperty("java.net.preferIPv4Stack", "true")
            
            // JmDNS.create() with no arguments is the most reliable way on Windows as it handles 
            // multi-homed interface selection and prevents setsockopt errors.
            jmdns = JmDNS.create()
            
            val serviceInfo = ServiceInfo.create(
                "_ibviewer._tcp.local.",
                "IB Viewer Server",
                port,
                0, 0, // weight, priority
                mapOf("path" to "/")
            )
            
            jmdns?.registerService(serviceInfo)
            logger.info("Registered mDNS service: _ibviewer._tcp.local. on port $port")
        } catch (e: Exception) {
            logger.error("Failed to start mDNS discovery service: ${e.message}")
        }
    }

    fun stop() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (e: Exception) {
            // Ignore closure errors
        }
        jmdns = null
        logger.info("Stopped mDNS discovery service")
    }
}
