package com.portfoliohelper.data.repository

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.portfoliohelper.data.model.CashEntry
import com.portfoliohelper.data.model.Position
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

class SyncRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val SERVICE_TYPE = "_portfoliohelper._tcp"

    /** Build an HttpClient that trusts only the cert matching [fingerprint], or any cert if null. */
    private fun httpsClient(fingerprint: String?): HttpClient {
        val trustManager = FingerprintTrustManager(fingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<X509TrustManager>(trustManager), SecureRandom())
        }
        return HttpClient(OkHttp) {
            engine {
                preconfigured = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .hostnameVerifier { _: String, _: SSLSession -> true }
                    .build()
            }
        }
    }

    fun discoverServers(): Flow<List<NsdServiceInfo>> = callbackFlow {
        val servers = mutableMapOf<String, NsdServiceInfo>()

        Log.d("SyncRepository", "Starting discovery for $SERVICE_TYPE")

        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("portfoliohelper_multicast_lock").apply {
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
                if (service.serviceType.contains("_portfoliohelper")) {
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

    /**
     * Pair with the server over HTTPS (accept-any cert on first contact to capture fingerprint).
     * On success, stores serverAssignedId, aesKey, and TLS fingerprint in settings.
     */
    suspend fun pair(host: String, port: Int, pin: String) {
        val clientId = settings.getDeviceId()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Accept any cert during pairing — we'll pin the fingerprint immediately after
        val client = httpsClient(fingerprint = null)
        try {
            var capturedFingerprint: String? = null

            // Use accept-any client to capture the cert fingerprint on first connection
            val pairClient = HttpClient(OkHttp) {
                engine {
                    val captureTrustManager = object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                            if (chain.isNotEmpty()) {
                                capturedFingerprint = FingerprintTrustManager.fingerprintOf(chain[0])
                                Log.d("SyncRepository", "Captured TLS fingerprint: $capturedFingerprint")
                            }
                        }
                    }
                    val sslCtx = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf<X509TrustManager>(captureTrustManager), SecureRandom())
                    }
                    preconfigured = OkHttpClient.Builder()
                        .sslSocketFactory(sslCtx.socketFactory, captureTrustManager)
                        .hostnameVerifier { _: String, _: SSLSession -> true }
                        .build()
                }
            }

            val response = pairClient.post("https://$host:$port/api/sync/pair") {
                parameter("pin", pin)
                header("X-Device-ID", clientId)
                header("X-Device-Name", deviceName)
            }

            if (response.status != HttpStatusCode.OK) {
                val body = response.bodyAsText()
                throw Exception(if (body.isNotBlank()) body else "Pairing failed: ${response.status}")
            }

            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).jsonObject
            val serverAssignedId = json["serverAssignedId"]?.jsonPrimitive?.content
                ?: throw Exception("Missing serverAssignedId in response")
            val aesKey = json["aesKey"]?.jsonPrimitive?.content
                ?: throw Exception("Missing aesKey in response")

            settings.saveServerAssignedId(serverAssignedId)
            settings.saveAesKey(aesKey)
            capturedFingerprint?.let { settings.saveTlsFingerprint(it) }

            Log.i("SyncRepository", "Paired successfully. serverAssignedId=$serverAssignedId, fingerprint=$capturedFingerprint")
            pairClient.close()
        } finally {
            client.close()
        }
    }

    suspend fun sync() {
        val serverInfo = settings.syncServerInfo.firstOrNull() ?: run {
            Log.w("SyncRepository", "Sync skipped: No paired server")
            return
        }

        Log.d("SyncRepository", "Starting sync with ${serverInfo.name}")

        val resolved = findServer(serverInfo.name)
        val host = resolved?.host?.hostAddress ?: serverInfo.host
        val port = resolved?.port ?: serverInfo.port

        if (host.isEmpty()) {
            Log.e("SyncRepository", "Sync failed: Host unknown")
            throw Exception("Could not find server IP. Ensure you are on the same WiFi.")
        }

        val serverAssignedId = settings.getServerAssignedId()
            ?: throw UnauthorizedException()
        val aesKey = settings.getAesKey()
            ?: throw UnauthorizedException()
        val fingerprint = settings.getTlsFingerprint()

        val client = httpsClient(fingerprint)
        try {
            val response = client.get("https://$host:$port/api/sync/data") {
                header("X-Device-ID", serverAssignedId)
            }

            if (response.status == HttpStatusCode.Unauthorized) throw UnauthorizedException()
            if (!response.status.isSuccess()) throw Exception("Sync failed: ${response.status}")

            val encryptedBytes = response.readBytes()
            val jsonBytes = AesGcm.decrypt(encryptedBytes, aesKey)
            val root = Json.decodeFromString<BackupRoot>(jsonBytes.toString(Charsets.UTF_8))

            parseAndSave(root)
            Log.i("SyncRepository", "Sync successful: ${root.stocks.size} positions, ${root.cash.size} cash entries")

            settings.saveSyncServerInfo(serverInfo.copy(host = host, port = port))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is UnauthorizedException) throw e
            Log.e("SyncRepository", "Sync failed: ${e.message}", e)
            throw Exception("Connection failed: ${e.message}")
        } finally {
            client.close()
        }
    }

    private suspend fun findServer(name: String): NsdServiceInfo? = withTimeoutOrNull(10000) {
        discoverServers().firstOrNull { list ->
            list.any { it.serviceName == name }
        }?.find { it.serviceName == name }
    }

    private suspend fun parseAndSave(root: BackupRoot) {
        val positions = root.stocks.map { s ->
            Position(
                symbol = s.symbol,
                quantity = s.amount,
                targetWeight = s.targetWeight,
                groups = s.groups
            )
        }

        val cashEntries = root.cash.mapNotNull { c ->
            when {
                c.currency == "P" -> {
                    // Use snapshotUsd if present; skip entry if not resolvable
                    val usd = c.snapshotUsd ?: return@mapNotNull null
                    CashEntry(
                        label = c.label,
                        currency = "USD",
                        amount = usd,
                        isMargin = c.marginFlag
                    )
                }
                else -> CashEntry(
                    label = c.label,
                    currency = c.currency,
                    amount = c.amount,
                    isMargin = c.marginFlag
                )
            }
        }

        db.withTransaction {
            db.positionDao().hardDeleteAll()
            positions.forEach { db.positionDao().upsert(it) }

            db.cashDao().deleteAll()
            cashEntries.forEach { db.cashDao().upsert(it) }
        }
    }

    class UnauthorizedException : Exception("Device not paired or authentication failed")
}

// BackupRoot models — mirror of server BackupService data classes
@kotlinx.serialization.Serializable
private data class BackupRoot(
    val version: Int = 1,
    val portfolioSlug: String,
    val stocks: List<BackupStock>,
    val cash: List<BackupCash>
)

@kotlinx.serialization.Serializable
private data class BackupStock(
    val symbol: String,
    val amount: Double,
    val targetWeight: Double = 0.0,
    val letf: String = "",
    val groups: String = ""
)

@kotlinx.serialization.Serializable
private data class BackupCash(
    val key: String,
    val label: String,
    val currency: String,
    val marginFlag: Boolean,
    val amount: Double,
    val portfolioRef: String? = null,
    val snapshotUsd: Double? = null
)
