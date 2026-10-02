package com.cncverse.stremiobridge.tunnel

import com.cncverse.stremiobridge.repo.getExtensionSetting
import com.cncverse.stremiobridge.repo.setExtensionSetting

/**
 * Reads/writes go through the shared in-memory settings map. Reading the file
 * directly and saving it back raced with the map's own saves: a stale or
 * half-written copy of the file overwrote everyone's extension settings.
 */
object DeviceIdManager {
    private const val KEY_DEVICE_ID = "KEY_DEVICE_ID"
    private const val KEY_CUSTOM_DOMAIN = "KEY_CUSTOM_DOMAIN"
    const val DEFAULT_DOMAIN = "cncverse.dpdns.org"

    private val lock = Any()

    fun getDeviceId(): String = synchronized(lock) {
        val existing = getExtensionSetting(KEY_DEVICE_ID)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        // Generate a new persistent 8-character hex device ID
        val charPool = "abcdefghijklmnopqrstuvwxyz0123456789"
        val randomStr = (1..8)
            .map { kotlin.random.Random.nextInt(0, charPool.length) }
            .map(charPool::get)
            .joinToString("")

        val newDeviceId = "cnc-$randomStr"
        setExtensionSetting(KEY_DEVICE_ID, newDeviceId)
        return newDeviceId
    }

    fun getCustomDomain(): String {
        return getExtensionSetting(KEY_CUSTOM_DOMAIN)?.ifBlank { DEFAULT_DOMAIN } ?: DEFAULT_DOMAIN
    }

    fun setCustomDomain(domain: String) {
        setExtensionSetting(KEY_CUSTOM_DOMAIN, domain)
    }

    fun getDeviceSubdomainUrl(): String {
        val deviceId = getDeviceId()
        val domain = getCustomDomain()
        return "https://$deviceId.$domain"
    }

    fun getTunnelToken(): String? {
        return getExtensionSetting("KEY_TUNNEL_TOKEN")
    }

    fun setTunnelToken(token: String) {
        setExtensionSetting("KEY_TUNNEL_TOKEN", token)
    }
}
