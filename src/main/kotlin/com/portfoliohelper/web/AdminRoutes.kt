package com.portfoliohelper.web

import com.portfoliohelper.service.AdminService
import com.portfoliohelper.service.PairingService
import com.portfoliohelper.util.appJson
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AdminRoutes")

private const val ADMIN_COOKIE = "admin_session"

@Serializable
private data class AdminLoginRequest(val passcode: String)

fun Route.configureAdminRoutes() {

    // Auth interceptor for all /api/paired-devices and /api/unpair
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (path != "/api/paired-devices" && path != "/api/unpair") return@intercept

        val token = call.request.cookies[ADMIN_COOKIE]
        if (token == null || !AdminService.validateSession(token)) {
            call.respond(HttpStatusCode.Unauthorized, "Admin session required")
            return@intercept finish()
        }
    }

    /** Admin login page */
    get("/admin") {
        call.respondText(adminLoginPage(), ContentType.Text.Html)
    }

    /** Admin login API — verifies passcode, sets session cookie */
    post("/api/admin/login") {
        val body = try {
            call.receiveText().let { appJson.decodeFromString<AdminLoginRequest>(it) }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid request body")
            return@post
        }

        if (AdminService.verifyPasscode(body.passcode)) {
            val token = AdminService.createSession()
            call.response.cookies.append(
                Cookie(
                    name = ADMIN_COOKIE,
                    value = token,
                    httpOnly = true,
                    secure = true,
                    path = "/",
                    maxAge = 8 * 60 * 60 // 8 hours
                )
            )
            logger.info("Admin login successful from ${call.request.origin.remoteHost}")
            call.respond(HttpStatusCode.OK, "Login successful")
        } else {
            logger.warn("Admin login failed from ${call.request.origin.remoteHost}")
            call.respond(HttpStatusCode.Unauthorized, "Invalid passcode")
        }
    }

    /** List all paired devices */
    get("/api/paired-devices") {
        val devices = PairingService.getPairedClients().map { client ->
            buildJsonObject {
                put("serverAssignedId", client.serverAssignedId)
                put("name", client.name)
                put("clientId", client.clientId)
                put("pairedAt", client.pairedAt)
                put("lastIp", client.lastIp)
            }
        }
        call.respondText(
            appJson.encodeToString(kotlinx.serialization.json.JsonArray.serializer(),
                kotlinx.serialization.json.JsonArray(devices)),
            ContentType.Application.Json
        )
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
  .hint { font-size: .8rem; color: #888; margin: 0 0 1rem; }
</style>
</head>
<body>
<div class="card">
  <h1>Admin Login</h1>
  <p class="hint">Paste the passcode from the system tray "Copy Admin Code" menu item.</p>
  <input type="password" id="code" placeholder="Admin passcode" autocomplete="off">
  <button onclick="login()">Login</button>
  <div id="msg"></div>
</div>
<script>
async function login() {
  const code = document.getElementById('code').value.trim();
  const msg = document.getElementById('msg');
  msg.textContent = '';
  const r = await fetch('/api/admin/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ passcode: code })
  });
  if (r.ok) {
    window.location.href = '/admin/devices';
  } else {
    msg.textContent = await r.text();
  }
}
document.getElementById('code').addEventListener('keydown', e => { if (e.key === 'Enter') login(); });
</script>
</body>
</html>
""".trimIndent()
