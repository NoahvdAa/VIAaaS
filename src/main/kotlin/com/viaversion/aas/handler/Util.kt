package com.viaversion.aas.handler

import com.viaversion.aas.AspirinServer
import com.viaversion.aas.config.VIAaaSConfig
import com.viaversion.aas.codec.packet.Packet
import com.viaversion.aas.readRemainingBytes
import com.viaversion.aas.send
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.api.type.Type
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelPipeline
import io.netty.handler.proxy.HttpProxyHandler
import io.netty.handler.proxy.Socks4ProxyHandler
import io.netty.handler.proxy.Socks5ProxyHandler
import java.net.InetSocketAddress

fun forward(handler: MinecraftHandler, packet: Packet, flush: Boolean = false) {
    send(handler.other!!, packet, flush)
}

fun is17(handler: MinecraftHandler) = handler.data.frontVer!! <= ProtocolVersion.v1_7_6.version

fun addProxyHandler(pipe: ChannelPipeline) {
    val proxyUri = VIAaaSConfig.backendProxy
    if (proxyUri != null) {
        val socket = InetSocketAddress(AspirinServer.dnsResolver.resolve(proxyUri.host).get(), proxyUri.port)
        val user = proxyUri.userInfo?.substringBefore(':')
        val pass = proxyUri.userInfo?.substringAfter(':')
        when (proxyUri.scheme) {
            "socks5" -> pipe.addFirst(Socks5ProxyHandler(socket, user, pass))
            "socks4" -> pipe.addFirst(Socks4ProxyHandler(socket, user))
            "http" -> pipe.addFirst(if (user != null) HttpProxyHandler(socket, user, pass) else HttpProxyHandler(socket))
        }
    }
}

fun decodeBrand(data: ByteArray, is17: Boolean): String {
    return if (is17) {
        String(data, Charsets.UTF_8)
    } else {
        Type.STRING.read(Unpooled.wrappedBuffer(data))
    }
}

fun encodeBrand(string: String, is17: Boolean): ByteArray {
    return if (is17) {
        string.toByteArray(Charsets.UTF_8)
    } else {
        val buf = ByteBufAllocator.DEFAULT.buffer()
        try {
            Type.STRING.write(buf, string)
            readRemainingBytes(buf)
        } finally {
            buf.release()
        }
    }
}