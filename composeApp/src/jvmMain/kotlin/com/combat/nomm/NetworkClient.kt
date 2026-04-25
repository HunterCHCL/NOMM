package com.combat.nomm

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

object NetworkClient {
    private val proxyConfig: ProxyConfig? = detectSystemProxy()

    val client = HttpClient(CIO) {
        engine {
            proxyConfig?.let { proxy = it }
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }
        install(UserAgent) {
            agent = "NOMM-Updater/1.0"
        }
        install(HttpRedirect)
    }

    suspend fun fetchManifest(url: String): List<Extension>? = withContext(Dispatchers.IO) {
        runCatching {
            val response = client.get(url)
            if (response.status.isSuccess()) {
                val manifest = RepoMods.json.decodeFromString<Manifest>(response.body())
                SettingsManager.updateConfig(SettingsManager.config.value.copy(cachedManifest = manifest))
                manifest
            } else if (response.status == HttpStatusCode.NotFound) {
                if (SettingsManager.config.value.manifestUrl == "https://kopterbuzz.github.io/NOModManifestTesting/manifest/manifest.json") {
                    SettingsManager.updateConfig(SettingsManager.config.value.copy(manifestUrl = "https://kopterbuzz.github.io/NOMNOM/manifest/manifest.json"))
                    fetchManifest(url)
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrElse { e ->
            e.printStackTrace()
            ErrorNotifications.push(
                "Failed to fetch mod list",
                "URL: $url\n${e.javaClass.simpleName}: ${e.localizedMessage}\n\nUsing cached manifest. Check your internet connection and try refreshing."
            )
            null
        }?.distinctBy { it.id }
    }
}

private fun detectSystemProxy(): ProxyConfig? {
    if (System.getProperty("java.net.useSystemProxies") == null) {
        System.setProperty("java.net.useSystemProxies", "true")
    }
    return runCatching {
        val proxies = ProxySelector.getDefault().select(URI("https://github.com"))
        val proxy = proxies.firstOrNull { it.type() != Proxy.Type.DIRECT } ?: return null
        val addr = proxy.address() as? InetSocketAddress ?: return null
        when (proxy.type()) {
            Proxy.Type.HTTP -> {
                println("NOMM: Detected HTTP proxy: ${addr.hostName}:${addr.port}")
                ProxyBuilder.http("http://${addr.hostName}:${addr.port}")
            }
            Proxy.Type.SOCKS -> {
                println("NOMM: Detected SOCKS proxy: ${addr.hostName}:${addr.port}")
                ProxyBuilder.socks(addr.hostName, addr.port)
            }
            else -> null
        }
    }.getOrElse { e ->
        println("NOMM: Proxy detection failed: ${e.localizedMessage}")
        null
    }
}