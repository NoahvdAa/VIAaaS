package com.viaversion.aas

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.velocitypowered.natives.util.Natives
import com.viaversion.aas.config.VIAaaSConfig
import com.viaversion.aas.handler.FrontEndInit
import com.viaversion.aas.handler.MinecraftHandler
import com.viaversion.aas.platform.AspirinPlatform
import com.viaversion.aas.web.WebDashboardServer
import com.viaversion.viaversion.ViaManagerImpl
import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.api.protocol.packet.State
import com.viaversion.viaversion.update.Version
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.network.tls.certificates.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelOption
import io.netty.channel.WriteBufferWaterMark
import io.netty.resolver.dns.DnsNameResolverBuilder
import io.netty.util.concurrent.Future
import java.io.File
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.concurrent.CompletableFuture

object AspirinServer {
    var ktorServer: NettyApplicationEngine? = null
    val version = JsonParser.parseString(
        AspirinPlatform::class.java.classLoader
            .getResourceAsStream("viaaas_info.json")!!
            .reader(Charsets.UTF_8)
            .readText()
    ).asJsonObject["version"].asString
    val cleanedVer get() = version.substringBefore("+")
    var viaWebServer = WebDashboardServer()
    private var serverFinishing = CompletableFuture<Unit>()
    private var finishedFuture = CompletableFuture<Unit>()
    private val initFuture = CompletableFuture<Unit>()
    val bufferWaterMark = WriteBufferWaterMark(512 * 1024, 2048 * 1024)

    // Minecraft crypto is very cursed: https://github.com/VelocityPowered/Velocity/issues/568
    var mcCryptoKey = generateKey()
    fun generateKey(): KeyPair {
        return KeyPairGenerator.getInstance("RSA").let {
            it.initialize(2048)
            it.genKeyPair()
        }
    }

    init {
        // This VIAaaS code idea is even more cursed
        AspirinPlatform.runRepeatingSync({
            mcCryptoKey = generateKey()
        }, 10 * 60 * 20L) // regenerate each 10 min
    }

    val parentLoop = eventLoopGroup()
    val childLoop = eventLoopGroup()
    var chFuture: ChannelFuture? = null
    val dnsResolver = DnsNameResolverBuilder(childLoop.next())
        .socketChannelFactory(channelSocketFactory(childLoop))
        .channelFactory(channelDatagramFactory(childLoop))
        .build()
    val httpClient = HttpClient(Java) {
        install(UserAgent) {
            agent = "VIAaaS/$cleanedVer"
        }
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    fun finish() {
        try {
            Via.getManager().connectionManager.connections.forEach {
                it.channel?.pipeline()?.get(MinecraftHandler::class.java)?.disconnect("Stopping")
            }

            (Via.getManager() as ViaManagerImpl).destroy()
        } finally {
            mainFinishSignal()
            ktorServer?.stop(1000, 1000)
            httpClient.close()
            listOf<Future<*>?>(
                chFuture?.channel()?.close(),
                parentLoop.shutdownGracefully(),
                childLoop.shutdownGracefully()
            )
                .forEach { it?.sync() }
        }
    }

    fun waitStopSignal() = serverFinishing.join()
    fun waitMainFinish() = finishedFuture.join()
    fun waitMainStart() = initFuture.join()

    fun wasStopSignalFired() = serverFinishing.isDone

    fun stopSignal() = serverFinishing.complete(Unit)
    fun mainFinishSignal() = finishedFuture.complete(Unit)
    fun mainStartSignal() = initFuture.complete(Unit)

    fun listenPorts(args: Array<String>) {
        chFuture = ServerBootstrap()
            .group(parentLoop, childLoop)
            .channelFactory(channelServerSocketFactory(parentLoop))
            .childHandler(FrontEndInit)
            .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, bufferWaterMark)
            .childOption(ChannelOption.IP_TOS, 0x18)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .bind(InetAddress.getByName(VIAaaSConfig.bindAddress), VIAaaSConfig.port)

        ktorServer = embeddedServer(Netty, commandLineEnvironment(args)) {}.start(false)

        viaaasLogger.info("Using compression: ${Natives.compress.loadedVariant}, crypto: ${Natives.cipher.loadedVariant}")
        viaaasLogger.info("Binded minecraft into " + chFuture!!.sync().channel().localAddress())
    }

    fun generateCert() {
        File("config/https.jks").apply {
            parentFile.mkdirs()
            if (!exists()) generateCertificate(this)
        }
    }

    fun addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread {
            stopSignal()
            waitMainFinish()
        })
    }

    fun currentPlayers(): Int {
        return Via.getManager().connectionManager.connections.filter { it.protocolInfo.state == State.PLAY }.count()
    }

    suspend fun updaterCheckMessage(): String {
        return try {
            val latestData =
                httpClient.get<JsonObject>("https://api.github.com/repos/viaversion/viaaas/releases/latest")
            val latest = Version(latestData["tag_name"]!!.asString.removePrefix("v"))
            val current = Version(cleanedVer)
            when {
                latest > current -> "This build is outdated. Latest is $latest"
                latest < current -> "This build is newer than released."
                else -> "VIAaaS seems up to date."
            }
        } catch (e: Exception) {
            "Failed to fetch latest release info. $e"
        }
    }
}