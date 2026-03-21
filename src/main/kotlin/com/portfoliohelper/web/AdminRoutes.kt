package com.portfoliohelper.web

import com.portfoliohelper.service.AdminService
import com.portfoliohelper.service.CodeResult
import com.portfoliohelper.service.PairingService
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AdminRoutes")

@Serializable
private data class AdminLoginRequest(val passcode: String)

@Serializable
private data class PairedDeviceResponse(
    val serverAssignedId: String,
    val name: String,
    val clientId: String,
    val pairedAt: Long,
    val lastIp: String
)

@Serializable
private data class SessionInfoResponse(
    val token: String,
    val createdAt: Long,
    val ip: String,
    val userAgent: String,
    val isCurrent: Boolean
)

fun Route.configureAdminRoutes() {

    /** Admin login page — no code embedded; user generates on demand */
    get("/admin") {
        call.respondText(adminLoginPage(), ContentType.Text.Html)
    }

    /** Admin login API — verifies one-time code, sets session cookie */
    post("/api/admin/login") {
        val body = try {
            call.receiveText().let { appJson.decodeFromString<AdminLoginRequest>(it) }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            return@post
        }

        val ip = call.request.origin.remoteHost
        if (AdminService.isBlocked(ip)) {
            logger.warn("Admin login attempt blocked for IP $ip (rate limited)")
            call.respond(
                HttpStatusCode.TooManyRequests,
                "Too many failed attempts. Try again later."
            )
            return@post
        }

        when (AdminService.verifyAndConsume(body.passcode)) {
            is CodeResult.Success -> {
                AdminService.recordSuccess(ip)
                val userAgent = call.request.headers[HttpHeaders.UserAgent] ?: ""
                val token = AdminService.createSession(ip, userAgent)
                call.response.cookies.append(
                    Cookie(
                        name = AdminService.SESSION_COOKIE,
                        value = token,
                        httpOnly = true,
                        secure = true,
                        path = "/",
                        maxAge = 10 * 365 * 24 * 60 * 60,
                        extensions = mapOf("SameSite" to "Strict")
                    )
                )
                logger.info("Admin login successful from $ip")
                call.respond(HttpStatusCode.OK, "Login successful")
            }

            is CodeResult.Expired -> {
                AdminService.recordFailure(ip)
                logger.warn("Admin login with expired code from $ip")
                call.respond(HttpStatusCode.Gone, "Code has expired. Generate a new one.")
            }

            is CodeResult.Invalid -> {
                AdminService.recordFailure(ip)
                logger.warn("Admin login failed from $ip")
                call.respond(HttpStatusCode.Unauthorized, "Invalid code.")
            }
        }
    }

    /** List all paired devices */
    get("/api/paired-devices") {
        val devices = PairingService.getPairedClients().map { client ->
            PairedDeviceResponse(
                serverAssignedId = client.serverAssignedId,
                name = client.name,
                clientId = client.clientId,
                pairedAt = client.pairedAt,
                lastIp = client.lastIp
            )
        }
        call.respondText(appJson.encodeToString(devices), ContentType.Application.Json)
    }

    /** List all admin sessions with isCurrent flag */
    get("/api/admin/sessions") {
        val currentToken = call.request.cookies[AdminService.SESSION_COOKIE]
        val sessions = AdminService.getSessions().map { s ->
            SessionInfoResponse(
                token = s.token,
                createdAt = s.createdAt,
                ip = s.ip,
                userAgent = s.userAgent,
                isCurrent = s.token == currentToken
            )
        }
        call.respondText(appJson.encodeToString(sessions), ContentType.Application.Json)
    }

    /** Remove a specific admin session (cannot remove own session) */
    delete("/api/admin/session") {
        val token = call.request.queryParameters["token"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing token parameter")
        val currentToken = call.request.cookies[AdminService.SESSION_COOKIE]
        if (token == currentToken) {
            return@delete call.respond(HttpStatusCode.Conflict, "Cannot remove current session")
        }
        AdminService.invalidateSession(token)
        call.respond(HttpStatusCode.OK, "Session removed")
    }

    /** Unpair all paired devices */
    post("/api/unpair-all") {
        PairingService.unpairAll()
        call.respond(HttpStatusCode.OK, "All devices unpaired")
    }

    /** Unpair a specific device */
    delete("/api/unpair") {
        val id = call.request.queryParameters["id"]
            ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id parameter")
        val removed = PairingService.unpairClient(id)
        if (removed) {
            call.respond(HttpStatusCode.OK, "Device unpaired")
        } else {
            call.respond(HttpStatusCode.NotFound, "Device not found")
        }
    }
}

private fun adminLoginPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Admin — Portfolio Helper</title>
<style>
  body { font-family: system-ui, sans-serif; display: flex; justify-content: center; align-items: center;
         min-height: 100vh; margin: 0; background: #1a1a2e; color: #eee; }
  .card { background: #16213e; border-radius: 12px; padding: 2rem; width: 360px; box-shadow: 0 4px 24px rgba(0,0,0,.4); }
  h1 { margin: 0 0 1.5rem; font-size: 1.4rem; }
  input { width: 100%; padding: .6rem .8rem; border: 1px solid #444; border-radius: 6px;
          background: #0f3460; color: #eee; font-size: 1rem; box-sizing: border-box; letter-spacing: .15em; }
  button { width: 100%; margin-top: 1rem; padding: .7rem; border: none; border-radius: 6px;
           background: #e94560; color: #fff; font-size: 1rem; cursor: pointer; }
  button:hover { background: #c73652; }
  #msg { margin-top: .8rem; font-size: .9rem; color: #f87171; min-height: 1.2em; }
  #msg.ok { color: #4ade80; }
  .hint { font-size: .8rem; color: #888; margin: 0 0 1rem; }
</style>
</head>
<body>
<div class="card">
  <h1>Admin Login</h1>
  <p class="hint">Use "Copy Admin Code" from the system tray menu, then paste it below.</p>
  <input type="password" id="code" placeholder="Paste code here" autocomplete="off">
  <button onclick="login()">Authorize this Browser</button>
  <div id="msg"></div>
</div>
<script>
async function login() {
  const code = document.getElementById('code').value.trim();
  const msg = document.getElementById('msg');
  msg.textContent = '';
  msg.className = '';
  const r = await fetch('/api/admin/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ passcode: code })
  });
  if (r.ok) {
    msg.textContent = 'Authorized. Redirecting…';
    msg.className = 'ok';
    window.location.href = '/';
  } else if (r.status === 410) {
    msg.textContent = 'This code has expired. Generate a new one.';
  } else if (r.status === 429) {
    msg.textContent = 'Too many failed attempts. Try again later.';
  } else {
    msg.textContent = 'Invalid code.';
  }
}
document.getElementById('code').addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
</script>
</body>
</html>
""".trimIndent()
