package com.ibviewer.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.ibviewer.data.model.CashEntry
import com.ibviewer.data.model.Position
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import java.net.InetAddress

class SyncRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val client = HttpClient(OkHttp)
    private val SERVICE_TYPE = "_ibviewer._tcp"

    fun discoverServers(): Flow<List<NsdServiceInfo>> = callbackFlow {
        val servers = mutableMapOf<String, NsdServiceInfo>()
        
        Log.d("SyncRepository", "Starting discovery for $SERVICE_TYPE")

        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("ibviewer_multicast_lock").apply {
                setReferenceCounted(true)
            }
        }
        multicastLock?.acquire()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("SyncRepository", "Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("SyncRepository", "Service found: ${service.serviceName}")
                if (service.serviceType.contains("_ibviewer")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("SyncRepository", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("SyncRepository", "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                            servers[serviceInfo.serviceName] = serviceInfo
                            trySend(servers.values.toList())
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                servers.remove(service.serviceName)
                trySend(servers.values.toList())
            }

            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("SyncRepository", "Start discovery failed: $errorCode")
                close(Exception("Discovery failed: $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        
        awaitClose { 
            Log.d("SyncRepository", "Stopping discovery")
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) { }
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    suspend fun pair(host: String, port: Int, pin: String) {
        val deviceId = settings.getDeviceId()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val response = client.post("http://$host:$port/api/sync/pair") {
            parameter("pin", pin)
            header("X-Device-ID", deviceId)
            header("X-Device-Name", deviceName)
        }
        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            throw Exception(if (body.isNotBlank()) body else "Pairing failed: ${response.status}")
        }
    }

    suspend fun sync() {
        val serverInfo = settings.syncServerInfo.firstOrNull() ?: run {
            Log.w("SyncRepository", "Sync skipped: No paired server")
            return
        }
        
        Log.d("SyncRepository", "Starting sync with ${serverInfo.name}")
        
        // Re-discover to get latest IP if it changed
        val resolved = findServer(serverInfo.name)
        val host = resolved?.host?.hostAddress ?: serverInfo.host
        val port = resolved?.port ?: serverInfo.port
        
        if (host.isEmpty()) {
            Log.e("SyncRepository", "Sync failed: Host unknown")
            throw Exception("Could not find server IP. Ensure you are on the same WiFi.")
        }

        val deviceId = settings.getDeviceId()
        val baseUrl = "http://$host:$port/api/sync"
        Log.d("SyncRepository", "Syncing from $baseUrl")

        try {
            val positionsCsv = client.get("$baseUrl/positions.csv") {
                header("X-Device-ID", deviceId)
            }.bodyAsText()
            if (positionsCsv.contains("Device not paired")) throw UnauthorizedException()
            
            val cashCsv = client.get("$baseUrl/cash.csv") {
                header("X-Device-ID", deviceId)
            }.bodyAsText()
            if (cashCsv.contains("Device not paired")) throw UnauthorizedException()

            parseAndSave(positionsCsv, cashCsv)
            Log.i("SyncRepository", "Sync successful")
            
            settings.saveSyncServerInfo(serverInfo.copy(host = host, port = port))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is UnauthorizedException) throw e
            Log.e("SyncRepository", "Sync failed: ${e.message}", e)
            throw Exception("Connection failed: ${e.message}")
        }
    }

    private suspend fun findServer(name: String): NsdServiceInfo? = withTimeoutOrNull(10000) {
        discoverServers().firstOrNull { list ->
            list.any { it.serviceName == name }
        }?.find { it.serviceName == name }
    }

    private suspend fun parseAndSave(positionsCsv: String, cashCsv: String) {
        val positions = positionsCsv.lineSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val parts = line.split(",")
                    Position(
                        symbol = parts[0].trim(),
                        quantity = parts[1].trim().toDouble(),
                        targetWeight = parts.getOrNull(2)?.trim()?.toDoubleOrNull() ?: 0.0,
                        groups = parts.getOrNull(3)?.trim() ?: ""
                    )
                } catch (e: Exception) { null }
            }.toList()

        val cashEntries = cashCsv.lineSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                try {
                    val parts = line.split(",")
                    CashEntry(
                        label = parts[0].trim(),
                        currency = parts[1].trim(),
                        amount = parts[2].trim().toDouble(),
                        isMargin = parts.getOrNull(3)?.trim()?.toBoolean() ?: false
                    )
                } catch (e: Exception) { null }
            }.toList()

        db.withTransaction {
            db.positionDao().hardDeleteAll()
            positions.forEach { db.positionDao().upsert(it) }
            
            db.cashDao().deleteAll()
            cashEntries.forEach { db.cashDao().upsert(it) }
        }
    }

    class UnauthorizedException : Exception("Device not paired or authentication failed")
}
