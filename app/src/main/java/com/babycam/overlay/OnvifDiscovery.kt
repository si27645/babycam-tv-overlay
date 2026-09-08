package com.babycam.overlay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Best-effort ONVIF camera discovery: a WS-Discovery UDP probe to find camera IPs on the
 * LAN, plus a minimal hand-rolled ONVIF SOAP client to try to resolve an actual RTSP URI
 * once a device is picked.
 *
 * This intentionally avoids a full ONVIF/SOAP library dependency - the wire format needed
 * here (a handful of fixed request bodies, WS-Security UsernameToken digest auth, and
 * pulling a couple of tags back out of the response) is small enough to hand-write with
 * java.net + android.util.Base64 + java.security, keeping the build dependency-free.
 *
 * ONVIF stacks are notoriously inconsistent across camera vendors, so every step here is
 * best-effort: discovery finds *candidate* devices, and resolving the actual stream URI can
 * legitimately fail on cameras with a quirky/partial ONVIF implementation - callers should
 * treat a null result as "type the RTSP URL in by hand" rather than a bug.
 *
 * All functions here are blocking (real sockets/HTTP) - always call from a background thread.
 */
object OnvifDiscovery {

    data class DiscoveredCamera(
        val xAddr: String,
        val ipAddress: String,
        val scopesHint: String
    ) {
        /** A short human-readable label pulled out of the WS-Discovery Scopes, falling back to the IP. */
        val displayName: String
            get() {
                val nameScope = Regex("onvif://www\\.onvif\\.org/name/([^\\s\"]+)")
                    .find(scopesHint)?.groupValues?.get(1)
                return (nameScope?.replace('_', ' ')?.let { java.net.URLDecoder.decode(it, "UTF-8") }) ?: ipAddress
            }
    }

    private const val MULTICAST_ADDRESS = "239.255.255.250"
    private const val MULTICAST_PORT = 3702

    /**
     * Sends a single WS-Discovery Probe and collects ProbeMatch responses for [timeoutMs].
     * Acquires a WiFi multicast lock for the duration - several devices/ROMs silently drop
     * multicast packets on Wi-Fi without one, which would otherwise make discovery flaky.
     * Harmless no-op on boxes with no WiFi hardware (e.g. Ethernet-only).
     */
    fun probe(context: Context, timeoutMs: Int = 3000): List<DiscoveredCamera> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("babycam_onvif_discovery")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        return try {
            probeInternal(timeoutMs)
        } finally {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun probeInternal(timeoutMs: Int): List<DiscoveredCamera> {
        val results = mutableListOf<DiscoveredCamera>()
        val messageId = "urn:uuid:${UUID.randomUUID()}"
        val probeXml = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header>
<w:MessageID>$messageId</w:MessageID>
<w:To e:mustUnderstand="1">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
<w:Action e:mustUnderstand="1">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
</e:Header>
<e:Body>
<d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe>
</e:Body>
</e:Envelope>""".toByteArray(Charsets.UTF_8)

        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket()
            val group = InetAddress.getByName(MULTICAST_ADDRESS)
            socket.send(DatagramPacket(probeXml, probeXml.size, group, MULTICAST_PORT))

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 0) break
                socket.soTimeout = remaining
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (timeout: SocketTimeoutException) {
                    break
                }
                val xml = String(response.data, 0, response.length, Charsets.UTF_8)
                parseProbeMatch(xml, response.address.hostAddress ?: "")?.let { results.add(it) }
            }
        } catch (e: Exception) {
            // Discovery is best-effort; a failed socket just means an empty result list.
        } finally {
            socket?.close()
        }
        return results.distinctBy { it.xAddr }
    }

    private fun parseProbeMatch(xml: String, sourceIp: String): DiscoveredCamera? = runCatching {
        val xAddrsRaw = extractTag(xml, "XAddrs") ?: return null
        val firstAddr = xAddrsRaw.trim().split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: return null
        val scopes = extractTag(xml, "Scopes") ?: ""
        DiscoveredCamera(xAddr = firstAddr, ipAddress = sourceIp, scopesHint = scopes)
    }.getOrNull()

    /**
     * Attempts to resolve a real RTSP URI for a discovered device via
     * GetCapabilities -> GetProfiles -> GetStreamUri. Returns null if any step fails
     * (unsupported by this camera's firmware, wrong credentials, etc.) - the caller should
     * fall back to letting the user type the URL manually.
     */
    fun fetchStreamUri(xAddr: String, username: String, password: String): String? = runCatching {
        val capsBody = soapEnvelope(
            securityHeader(username, password),
            """<tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:Category>Media</tds:Category></tds:GetCapabilities>"""
        )
        val capsResponse = soapPost(xAddr, capsBody)
        val mediaXAddr = extractTag(capsResponse, "Media")?.let { extractTag(it, "XAddr") } ?: xAddr

        val profilesBody = soapEnvelope(
            securityHeader(username, password),
            """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>"""
        )
        val profilesResponse = soapPost(mediaXAddr, profilesBody)
        val token = Regex("token=\"([^\"]+)\"").find(profilesResponse)?.groupValues?.get(1) ?: return null

        val streamBody = soapEnvelope(
            securityHeader(username, password),
            """<trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">""" +
                """<trt:StreamSetup>""" +
                """<tt:Stream xmlns:tt="http://www.onvif.org/ver10/schema">RTP-Unicast</tt:Stream>""" +
                """<tt:Transport xmlns:tt="http://www.onvif.org/ver10/schema"><tt:Protocol>RTSP</tt:Protocol></tt:Transport>""" +
                """</trt:StreamSetup>""" +
                """<trt:ProfileToken>$token</trt:ProfileToken>""" +
                """</trt:GetStreamUri>"""
        )
        val streamResponse = soapPost(mediaXAddr, streamBody)
        extractTag(streamResponse, "Uri")
    }.getOrNull()

    private fun soapEnvelope(header: String, body: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope">
<e:Header>$header</e:Header>
<e:Body>$body</e:Body>
</e:Envelope>"""

    private fun securityHeader(username: String, password: String): String {
        if (username.isBlank()) return ""
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val digestInput = nonceBytes + created.toByteArray(Charsets.UTF_8) + password.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1").digest(digestInput)
        val digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        return """<Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" e:mustUnderstand="1">
<UsernameToken>
<Username>$username</Username>
<Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</Password>
<Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
<Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
</UsernameToken>
</Security>"""
    }

    private fun soapPost(url: String, body: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            connection.disconnect()
        }
    }

    /** Namespace-prefix-agnostic tag extraction - ONVIF vendors are inconsistent about SOAP prefixes. */
    private fun extractTag(xml: String, tag: String): String? =
        Regex("<(?:\\w+:)?$tag(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?$tag>")
            .find(xml)?.groupValues?.get(1)?.trim()
}
