package com.portfoliohelper.tws

import org.slf4j.LoggerFactory
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.*
import kotlin.random.Random

// ── Public Result Models ───────────────────────────────────────────────────────

data class StockPosition(
    val symbol: String,
    val exchange: String,           // e.g. "SBF", "NASDAQ", "NYSE"
    val currency: String,           // e.g. "USD", "EUR"
    val qty: Double,
    val avgCost: Double,
    val account: String
)

data class AccountSummary(
    val account: String,
    val cashBalances: Map<String, Double>,      // currency -> balance
    val accruedCash: Map<String, Double>,       // currency -> accrued cash / MTD interest
    val pendingDividends: Map<String, Double>   // currency -> accrued dividends
)

data class PortfolioSnapshot(
    val account: String,
    val positions: List<StockPosition>,
    val summary: AccountSummary
) {
    companion object {
        /**
         * Connects to TWS, fetches positions and account summary for the first
         * individual account (U-prefix), disconnects, and returns the result.
         *
         * @param host           TWS host, default localhost
         * @param port           7496 = live, 7497 = paper
         * @param account        optional account number, falls back to first U-prefix account
         * @param timeoutSeconds per-request timeout
         */
        fun fetch(
            host: String = "127.0.0.1",
            port: Int = 7496,
            account: String? = null,
            timeoutSeconds: Long = 10
        ): PortfolioSnapshot {
            val client = TwsClient(host = host, port = port)
            client.connect()
            val readerThread = client.startReaderThread()

            // allow TWS to send initial MANAGED_ACCTS and connection messages
            Thread.sleep(1000)

            val resolvedAccount = account
                ?: client.firstIndividualAccount()
                ?: error("No individual account (U-prefix) found in: ${client.managedAccounts}")

            val positions = client.getStockPositions(
                accountFilter = resolvedAccount,
                timeoutSeconds = timeoutSeconds
            )
            val summary = client.getAccountSummary(resolvedAccount, timeoutSeconds = timeoutSeconds)

            readerThread.interrupt()
            client.disconnect()

            return PortfolioSnapshot(
                account = resolvedAccount,
                positions = positions,
                summary = summary
            )
        }
    }
}

// ── TWS Message Constants ───────────────────────────────────────────────────────

private object OutMsg {
    const val REQ_ACCOUNT_UPDATES = 6
    const val REQ_POSITIONS = 61
    const val CANCEL_POSITIONS = 64
}

private object InMsg {
    const val ACCT_VALUE = 6
    const val ACCT_DOWNLOAD_END = 54
    const val ERR_MSG = 4
    const val MANAGED_ACCTS = 15
    const val POSITION = 61
    const val POSITION_END = 62
}

private object AcctKey {
    const val CASH_BALANCE = "CashBalance"
    const val ACCRUED_CASH = "AccruedCash"      // MTD interest earned/paid
    const val ACCRUED_DIVIDEND = "AccruedDividend"  // pending dividends
}

// ── TWS Socket Client (internal) ───────────────────────────────────────────────

internal class TwsClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 7496,
    private val clientId: Int = Random.nextInt(1, 10000)
) {
    private val logger = LoggerFactory.getLogger(TwsClient::class.java)
    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    private val positions = ConcurrentHashMap<String, StockPosition>()
    private var positionLatch = CountDownLatch(1)

    // account -> key -> currency -> value
    private val acctValues =
        ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Double>>>()
    private var acctLatch = CountDownLatch(1)
    private var acctSubscribed = false

    val managedAccounts = mutableListOf<String>()
    fun firstIndividualAccount(): String? = managedAccounts.firstOrNull { it.startsWith("U") }

    var serverVersion: Int = 0
        private set

    // ── Connect & Handshake ──────────────────────────────────────────────────

    fun connect() {
        socket = Socket()
        socket.connect(java.net.InetSocketAddress(InetAddress.getByName(host), port), 5000)
        input = DataInputStream(socket.getInputStream())
        output = socket.getOutputStream()

        sendHandshake()
        val (version, _) = readHandshakeResponse()
        serverVersion = version
        sendStartApi()

        logger.info("Connected to TWS server version={}", serverVersion)
    }

    fun disconnect() {
        if (acctSubscribed) cancelAccountUpdates("")
        runCatching { socket.close() }
        logger.info("Disconnected from TWS")
    }

    private fun sendHandshake() {
        val payload = "v100..187\u0000".toByteArray(Charsets.UTF_8)
        val header = ByteArray(4) { i -> ((payload.size shr ((3 - i) * 8)) and 0xFF).toByte() }
        output.write("API\u0000".toByteArray(Charsets.UTF_8))
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readHandshakeResponse(): Pair<Int, String> {
        val version = readRawString()
        val timestamp = readRawString()
        return Pair(version.toIntOrNull() ?: 0, timestamp)
    }

    private fun sendStartApi() {
        sendMessage(listOf("71", "2", clientId.toString(), ""))
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun getStockPositions(
        exchangeFilter: String? = null,
        accountFilter: String? = null,
        timeoutSeconds: Long = 10
    ): List<StockPosition> {
        positions.clear()
        positionLatch = CountDownLatch(1)

        requestPositions()
        val complete = positionLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        cancelPositions()

        if (!complete) logger.warn("Position stream timed out — partial results returned")

        return positions.values
            .filter {
                exchangeFilter == null || it.exchange.equals(
                    exchangeFilter,
                    ignoreCase = true
                )
            }
            .filter { accountFilter == null || it.account == accountFilter }
            .sortedBy { it.symbol }
    }

    fun getAccountSummary(
        account: String,
        timeoutSeconds: Long = 10
    ): AccountSummary {
        acctValues.clear()
        acctLatch = CountDownLatch(1)

        requestAccountUpdates(subscribe = true, account = account)
        acctSubscribed = true
        val complete = acctLatch.await(timeoutSeconds, TimeUnit.SECONDS)
        cancelAccountUpdates(account)
        acctSubscribed = false

        if (!complete) logger.warn("Account stream timed out — partial results returned")

        fun valuesFor(key: String): Map<String, Double> =
            acctValues[account]?.get(key)
                ?.filterKeys { it != "BASE" }
                ?.toMap() ?: emptyMap()

        return AccountSummary(
            account = account,
            cashBalances = valuesFor(AcctKey.CASH_BALANCE),
            accruedCash = valuesFor(AcctKey.ACCRUED_CASH),
            pendingDividends = valuesFor(AcctKey.ACCRUED_DIVIDEND)
        )
    }

    // ── Requests ─────────────────────────────────────────────────────────────

    private fun requestPositions() {
        sendMessage(listOf(OutMsg.REQ_POSITIONS.toString(), "1"))
    }

    private fun cancelPositions() {
        sendMessage(listOf(OutMsg.CANCEL_POSITIONS.toString(), "1"))
    }

    private fun requestAccountUpdates(subscribe: Boolean, account: String) {
        sendMessage(
            listOf(
                OutMsg.REQ_ACCOUNT_UPDATES.toString(),
                "2",
                if (subscribe) "1" else "0",
                account
            )
        )
    }

    private fun cancelAccountUpdates(account: String) {
        requestAccountUpdates(subscribe = false, account = account)
    }

    // ── Message Reader ────────────────────────────────────────────────────────

    fun readMessage(): Boolean {
        val fields = readFields() ?: return false
        if (fields.isEmpty()) return true

        when (fields[0].toIntOrNull()) {
            InMsg.POSITION -> handlePosition(fields)
            InMsg.POSITION_END -> {
                logger.debug("Position stream complete")
                positionLatch.countDown()
            }

            InMsg.ACCT_VALUE -> handleAcctValue(fields)
            InMsg.ACCT_DOWNLOAD_END -> {
                val account = if (fields.size >= 3) fields[2] else "?"
                logger.debug("Account download complete: {}", account)
                acctLatch.countDown()
            }

            InMsg.ERR_MSG -> {
                if (fields.size >= 5) {
                    val code = fields[3]
                    val msg = fields[4]
                    val infoOnly = code in listOf("2104", "2106", "2158", "2100", "2119")
                    if (infoOnly) logger.info("[{}] {}", code, msg)
                    else logger.warn("TWS error [{}]: {}", code, msg)
                }
            }

            InMsg.MANAGED_ACCTS -> {
                if (fields.size >= 3) {
                    managedAccounts.clear()
                    managedAccounts.addAll(
                        fields[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    )
                    logger.debug("Managed accounts: {}", managedAccounts)
                }
            }

            else -> { /* ignore */
            }
        }
        return true
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun handlePosition(fields: List<String>) {
        // [0]=msgId [1]=version [2]=account [3]=conId [4]=symbol [5]=secType
        // [6]=expiry [7]=strike [8]=right [9]=multiplier [10]=exchange
        // [11]=currency [12]=localSymbol [13]=tradingClass [14]=pos [15]=avgCost
        if (fields.size < 16) return
        if (fields[5] != "STK") return

        val account = fields[2]
        val symbol = fields[4].ifBlank { fields[12] }
        val qty = fields[14].toDoubleOrNull() ?: return
        if (qty == 0.0) return

        positions["$account:$symbol:${fields[10]}"] = StockPosition(
            symbol = symbol,
            exchange = fields[10],
            currency = fields[11],
            qty = qty,
            avgCost = fields[15].toDoubleOrNull() ?: 0.0,
            account = account
        )
    }

    private fun handleAcctValue(fields: List<String>) {
        // [0]=msgId [1]=version [2]=key [3]=value [4]=currency [5]=accountName
        if (fields.size < 6) return

        val key = fields[2]
        val value = fields[3].toDoubleOrNull() ?: return
        val currency = fields[4].ifBlank { "BASE" }
        val account = fields[5]

        logger.trace("[{}] {}  {} = {}", account, key, currency, value)

        if (key !in listOf(
                AcctKey.CASH_BALANCE,
                AcctKey.ACCRUED_CASH,
                AcctKey.ACCRUED_DIVIDEND
            )
        ) return

        acctValues
            .getOrPut(account) { ConcurrentHashMap() }
            .getOrPut(key) { ConcurrentHashMap() }[currency] = value
    }

    // ── Wire Helpers ──────────────────────────────────────────────────────────

    private fun sendMessage(fields: List<String>) {
        val payload = (fields.joinToString("\u0000") + "\u0000").toByteArray(Charsets.UTF_8)
        val len = payload.size
        val header = ByteArray(4) { i -> ((len shr ((3 - i) * 8)) and 0xFF).toByte() }
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readFields(): List<String>? {
        val lenBuf = ByteArray(4)
        runCatching { input.readFully(lenBuf) }.getOrElse { return null }

        val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                ((lenBuf[1].toInt() and 0xFF) shl 16) or
                ((lenBuf[2].toInt() and 0xFF) shl 8) or
                (lenBuf[3].toInt() and 0xFF)

        if (len !in 1..1_048_576) return emptyList()

        val buf = ByteArray(len)
        runCatching { input.readFully(buf) }.getOrElse { return null }

        return buf.toString(Charsets.UTF_8).split("\u0000").dropLastWhile { it.isEmpty() }
    }

    private fun readRawString(): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1 || b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}

// ── Reader Thread ──────────────────────────────────────────────────────────────

internal fun TwsClient.startReaderThread(): Thread {
    val t = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (!readMessage()) break
            }
        } catch (_: Exception) {
        }
    }, "tws-reader")
    t.isDaemon = true
    t.start()
    return t
}

// ── Main (usage example) ───────────────────────────────────────────────────────

fun main() {
    val snapshot = PortfolioSnapshot.fetch(port = 7496)

    println("\n─── Account: ${snapshot.account} ─────────────────────────")

    println("\n  Cash balances:")
    snapshot.summary.cashBalances.forEach { (ccy, amt) ->
        println("    $ccy : ${"%.2f".format(amt)}")
    }
    println("  Accrued cash (MTD interest):")
    snapshot.summary.accruedCash.forEach { (ccy, amt) ->
        println("    $ccy : ${"%.2f".format(amt)}")
    }
    println("  Pending dividends:")
    snapshot.summary.pendingDividends.forEach { (ccy, amt) ->
        println("    $ccy : ${"%.2f".format(amt)}")
    }

    println("\n  Stock positions (${snapshot.positions.size}):")
    snapshot.positions.forEach { p ->
        println(
            "    ${p.symbol.padEnd(10)} exchange=${p.exchange.padEnd(8)} " +
                    "qty=${p.qty}  avgCost=${p.avgCost}  ccy=${p.currency}"
        )
    }

    println("\n  By exchange:")
    snapshot.positions.groupBy { it.exchange }
        .forEach { (ex, list) -> println("    $ex : ${list.size} position(s)") }
}