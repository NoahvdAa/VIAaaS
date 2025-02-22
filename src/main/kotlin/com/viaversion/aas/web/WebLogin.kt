package com.viaversion.aas.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.viaversion.aas.*
import com.viaversion.aas.util.StacklessException
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.future.await
import java.net.URLEncoder
import java.time.Duration
import java.util.*
import kotlin.math.absoluteValue

class WebLogin : WebState {
    override suspend fun start(webClient: WebClient) {
        webClient.ws.send("""{"action": "ad_minecraft_id_login"}""")
        webClient.ws.flush()
    }

    private fun loginSuccessJson(username: String, uuid: UUID, token: String): JsonObject {
        return JsonObject().also {
            it.addProperty("action", "login_result")
            it.addProperty("success", true)
            it.addProperty("username", username)
            it.addProperty("uuid", uuid.toString())
            it.addProperty("token", token)
        }
    }

    private fun loginNotSuccess(): JsonObject {
        return JsonObject().also {
            it.addProperty("action", "login_result")
            it.addProperty("success", false)
        }
    }

    private suspend fun handleOfflineLogin(webClient: WebClient, msg: String, obj: JsonObject) {
        if (!sha512Hex(msg.toByteArray(Charsets.UTF_8)).startsWith("00000")) throw StacklessException("PoW failed")
        if ((obj.getAsJsonPrimitive("date").asLong - System.currentTimeMillis())
                .absoluteValue > Duration.ofSeconds(20).toMillis()
        ) {
            throw StacklessException("Invalid PoW date")
        }
        val username = obj["username"].asString.trim()
        val uuid = generateOfflinePlayerUuid(username)

        val token = webClient.server.generateToken(uuid, username)
        webClient.ws.send(loginSuccessJson(username, uuid, token).toString())

        webLogger.info("Token gen: ${webClient.id}: offline $username $uuid")
    }

    private suspend fun handleMcIdLogin(webClient: WebClient, msg: String, obj: JsonObject) {
            val username = obj["username"].asString
            val code = obj["code"].asString

            val check = AspirinServer.httpClient.submitForm<JsonObject>(
                "https://api.minecraft.id/gateway/verify/${URLEncoder.encode(username, Charsets.UTF_8)}",
                formParameters = parametersOf("code", code),
            )

            if (check.getAsJsonPrimitive("valid").asBoolean) {
                val mcIdUser = check["username"].asString
                val uuid = check["uuid"]?.asString?.let { parseUndashedId(it.replace("-", "")) }
                    ?: webClient.server.usernameIdCache[mcIdUser].await()
                    ?: throw StacklessException("Failed to get UUID from minecraft.id")

                val token = webClient.server.generateToken(uuid, mcIdUser)
                webClient.ws.send(loginSuccessJson(mcIdUser, uuid, token).toString())

                webLogger.info("Token gen: ${webClient.id}: $mcIdUser $uuid")
            } else {
                webClient.ws.send(loginNotSuccess().toString())
                webLogger.info("Token gen fail: ${webClient.id}: $username")
            }
    }

    private suspend fun handleListenLogins(webClient: WebClient, msg: String, obj: JsonObject) {
        val token = obj.getAsJsonPrimitive("token").asString
        val user = webClient.server.parseToken(token)
        val response = JsonObject().also {
            it.addProperty("action", "listen_login_requests_result")
            it.addProperty("token", token)
        }
        if (user != null && webClient.listenId(user.id)) {
            response.addProperty("success", true)
            response.addProperty("user", user.id.toString())
            response.addProperty("username", user.name)
            webLogger.info("Listen: ${webClient.id}: $user")
        } else {
            response.addProperty("success", false)
            webLogger.info("Listen fail: ${webClient.id}")
        }
        webClient.ws.send(response.toString())
    }

    private suspend fun handleUnlisten(webClient: WebClient, msg: String, obj: JsonObject) {
        val uuid = UUID.fromString(obj.getAsJsonPrimitive("uuid").asString)
        webLogger.info("Unlisten: ${webClient.id}: $uuid")
        val response = JsonObject().also {
            it.addProperty("action", "unlisten_login_requests_result")
            it.addProperty("uuid", uuid.toString())
            it.addProperty("success", webClient.unlistenId(uuid))
        }
        webClient.ws.send(response.toString())
    }

    private suspend fun handleSessionResponse(webClient: WebClient, msg: String, obj: JsonObject) {
        val hash = obj["session_hash"].asString
        webClient.server.sessionHashCallbacks.getIfPresent(hash)?.complete(Unit)
    }

    override suspend fun onMessage(webClient: WebClient, msg: String) {
        val obj = JsonParser.parseString(msg) as JsonObject

        when (obj.getAsJsonPrimitive("action").asString) {
            "offline_login" -> handleOfflineLogin(webClient, msg, obj)
            "minecraft_id_login" -> handleMcIdLogin(webClient, msg, obj)
            "listen_login_requests" -> handleListenLogins(webClient, msg, obj)
            "unlisten_login_requests" -> handleUnlisten(webClient, msg, obj)
            "session_hash_response" -> handleSessionResponse(webClient, msg, obj)
            else -> throw StacklessException("invalid action!")
        }

        webClient.ws.flush()
    }

    override suspend fun disconnected(webClient: WebClient) {
        webClient.listenedIds.forEach { webClient.unlistenId(it) }
    }

    override suspend fun onException(webClient: WebClient, exception: java.lang.Exception) {
    }
}
