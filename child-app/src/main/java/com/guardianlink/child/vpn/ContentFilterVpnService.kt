// child-app/src/main/java/com/guardianlink/child/vpn/ContentFilterVpnService.kt
package com.guardianlink.child.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.security.SecurePreferences
import com.guardianlink.common.util.DnsFilterPolicy
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DNS-only content filtering foundation.
 *
 * Scope of this service:
 * - It inspects DNS queries leaving the device when the VPN tunnel is active.
 * - It can match denylist / allowlist / wildcard rules and return synthetic NXDOMAIN responses.
 * - It is intentionally limited to DNS traffic on UDP/TCP port 53.
 * - It must only be enabled after explicit user consent for a VPN connection.
 *
 * It does not claim to be complete web filtering.
 *
 * What it cannot guarantee:
 * - alternative DNS resolvers (DoH, DoT, system-level DNS overrides)
 * - encrypted DNS (DNS-over-HTTPS / DNS-over-TLS)
 * - applications that use their own networking stack or direct sockets
 * - non-DNS traffic (HTTP, QUIC, TCP payload inspection, app-level interception)
 *
 * Stronger filtering requires a different architecture such as a managed proxy, WebView
 * restrictions, device policy enforcement, app allowlists, or network-level filtering that
 * the user has explicitly consented to and that is implemented in a platform-compliant way.
 */
class ContentFilterVpnService : VpnService() {

    companion object {
        const val ACTION_START = "guardianlink.vpn.START"
        const val ACTION_STOP = "guardianlink.vpn.STOP"

        /**
         * Locally-assigned (ULA-range) IPv6 address for the tunnel interface
         * itself. This does not need to be routable on the internet — like
         * the IPv4 10.0.0.2 address below, it only needs to exist so the OS
         * treats this VpnService as the device's IPv6 route too. Without
         * this, a dual-stack device's IPv6 DNS traffic bypasses the filter
         * entirely, even though the packet parser already supports parsing
         * and responding to it.
         */
        private const val TUNNEL_IPV6_ADDRESS = "fd00:1:fd00::2"
        private const val MAX_RESTART_BACKOFF_MS = 30_000L
        private const val BASE_RESTART_BACKOFF_MS = 1_000L

        /**
         * Whether *this app's own* content-filter VPN is currently
         * established. Consulted by [com.guardianlink.child.data.TamperDetector]
         * so it can distinguish "GuardianLink's filtering is active" from
         * "some VPN is active" (Android's `NetworkCapabilities` only exposes
         * the latter, generic signal — a child switching to a different VPN
         * app to bypass content filtering would otherwise still register as
         * "VPN enabled" / protected).
         */
        @Volatile
        var isActive: Boolean = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private val packetThread = AtomicBoolean(false)
    private var allowlistDomains: Set<String> = emptySet()
    private var denylistDomains: Set<String> = emptySet()
    private var dnsListener: ListenerRegistration? = null
    private var consecutiveFailures = 0
    private var ipv6RoutingActive = false
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            else -> {
                startVpn()
                loadSettings()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Belt-and-suspenders: onRevoke()/ACTION_STOP are the expected teardown
        // paths, but a service can be torn down other ways too (system-initiated
        // stop, process death). Without this, `isActive` could be left stuck
        // `true` after the VPN interface is actually gone, which would make
        // TamperDetector wrongly report "protected" — the opposite of what a
        // parental-control app's core trust promise requires.
        stopVpn()
    }

    private fun startVpn() {
        if (isRunning.get()) return

        try {
            val builder = Builder()
                .setSession("GuardianLink Content Filter")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .addDisallowedApplication(packageName)
                .setMtu(1500)

            // Best-effort IPv6: without this, IPv6 DNS queries on a dual-stack
            // network bypass this VPN interface entirely (the OS has no IPv6
            // route through it), even though the packet parser and
            // DnsResponseBuilder both support IPv6. If adding the IPv6
            // address/route fails for any reason (e.g. an unusual OEM
            // network stack), we deliberately do not let that abort IPv4
            // filtering — see ipv6RoutingActive, which callers (e.g. a
            // future protection-state indicator) can use to avoid silently
            // claiming full protection when only IPv4 is actually covered.
            ipv6RoutingActive = try {
                builder.addAddress(TUNNEL_IPV6_ADDRESS, 128)
                builder.addRoute("::", 0)
                true
            } catch (e: Exception) {
                Timber.w(e, "IPv6 routing unavailable for content filter VPN — falling back to IPv4-only")
                false
            }

            vpnInterface = builder.establish()

            isRunning.set(true)
            isActive = true
            consecutiveFailures = 0
            Timber.i("DNS VPN content filter started (ipv6=$ipv6RoutingActive)")
            startPacketProcessing()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start DNS VPN")
            stopVpn()
            scheduleRestartWithBackoff()
        }
    }

    /**
     * Bounded exponential backoff for VPN (re)start failures. Previously,
     * both the establish() failure path and the packet-loop-exit path could
     * call stopVpn()/startVpn() again immediately with no delay, so a
     * persistent failure (e.g. VPN permission revoked, another VPN app
     * active) could spin tightly and burn CPU/battery. This caps retries at
     * [MAX_RESTART_BACKOFF_MS] and resets the counter on any successful
     * start (see consecutiveFailures = 0 above).
     */
    private fun scheduleRestartWithBackoff() {
        consecutiveFailures++
        val delayMs = minOf(
            BASE_RESTART_BACKOFF_MS * (1L shl minOf(consecutiveFailures - 1, 5)),
            MAX_RESTART_BACKOFF_MS
        )
        Timber.w("Scheduling VPN restart in ${delayMs}ms (attempt #$consecutiveFailures)")
        restartHandler.postDelayed({ if (!isRunning.get()) startVpn() }, delayMs)
    }

    private fun stopVpn() {
        isRunning.set(false)
        isActive = false
        packetThread.set(false)
        restartHandler.removeCallbacksAndMessages(null)
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing VPN interface")
        } finally {
            vpnInterface = null
        }
    }

    private fun loadSettings() {
        val prefs = SecurePreferences(this)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        dnsListener?.remove()
        dnsListener = FirebaseFirestore.getInstance()
            .collection(FirebasePaths.COLLECTION_SETTINGS)
            .document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Timber.e(error, "Failed to load DNS filter settings")
                    return@addSnapshotListener
                }
                val allow = snapshot?.get("allowedDomains") as? List<String> ?: emptyList()
                val deny = snapshot?.get("blockedWebsites") as? List<String> ?: emptyList()
                allowlistDomains = allow.mapNotNull { DnsFilterPolicy.normalizeDomain(it) }.toSet()
                denylistDomains = deny.mapNotNull { DnsFilterPolicy.normalizeDomain(it) }.toSet()
                Timber.d("DNS filter settings updated: allow=${allowlistDomains.size}, deny=${denylistDomains.size}")
            }
    }

    private fun startPacketProcessing() {
        val fd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(fd)
        val outputStream = FileOutputStream(fd)

        val worker = Thread {
            val packet = ByteBuffer.allocate(32767)
            while (isRunning.get()) {
                try {
                    packet.clear()
                    val length = inputStream.read(packet.array())
                    if (length <= 0) {
                        if (isRunning.get()) Timber.w("VPN packet stream closed; restarting listener")
                        break
                    }

                    packet.limit(length)
                    val dnsPacket = parseDnsPacket(packet)
                    when {
                        dnsPacket == null -> {
                            outputStream.write(packet.array(), 0, length)
                        }
                        dnsPacket.queryDomain != null && shouldBlockDomain(dnsPacket.queryDomain) -> {
                            val response = buildNxDomainResponse(packet, length, dnsPacket)
                            if (response != null) {
                                outputStream.write(response)
                            } else {
                                outputStream.write(packet.array(), 0, length)
                            }
                            reportBlockedDomain(dnsPacket.queryDomain)
                        }
                        else -> {
                            outputStream.write(packet.array(), 0, length)
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Timber.e(e, "DNS VPN packet handling error")
                    }
                    break
                }
            }

            if (isRunning.get()) {
                Timber.w("DNS VPN packet loop exited; scheduling reconnect")
                stopVpn()
                scheduleRestartWithBackoff()
            }
        }

        packetThread.set(true)
        worker.isDaemon = true
        worker.start()
    }

    private fun shouldBlockDomain(domain: String): Boolean {
        val normalized = DnsFilterPolicy.normalizeDomain(domain) ?: return false
        val allowlist = allowlistDomains
        val denylist = denylistDomains

        if (allowlist.isNotEmpty() && allowlist.any { entry ->
                DnsFilterPolicy.normalizeDomain(entry)?.let { DnsFilterPolicy.normalizeDomain(normalized)?.equals(it) == true || normalized.endsWith(".$it") } == true
            }) {
            return false
        }

        return denylist.any { entry ->
            val rule = DnsFilterPolicy.normalizeDomain(entry) ?: return@any false
            DnsFilterPolicy.normalizeDomain(normalized)?.let { value ->
                val exactMatch = value == rule
                val subdomainMatch = value.endsWith(".$rule")
                val wildcardMatch = rule.startsWith("*.") && (value == rule.removePrefix("*.") || value.endsWith(".${rule.removePrefix("*.")}"))
                exactMatch || subdomainMatch || wildcardMatch
            } ?: false
        }
    }

    private fun parseDnsPacket(packet: ByteBuffer): DnsPacket? {
        if (packet.limit() < 20) return null

        val original = packet.duplicate()
        val version = (original.get(0).toInt() and 0xF0) ushr 4

        val transportProtocol: Int
        val dnsOffset: Int

        when (version) {
            4 -> {
                if (original.limit() < 20) return null
                val headerLength = (original.get(0).toInt() and 0x0F) * 4
                if (headerLength < 20 || original.limit() < headerLength + 8) return null
                transportProtocol = original.get(9).toInt() and 0xFF
                dnsOffset = headerLength + 8
                if (transportProtocol != OsConstants.IPPROTO_UDP && transportProtocol != OsConstants.IPPROTO_TCP) return null
            }
            6 -> {
                if (original.limit() < 40) return null
                transportProtocol = original.get(6).toInt() and 0xFF
                dnsOffset = 40
                if (transportProtocol != OsConstants.IPPROTO_UDP && transportProtocol != OsConstants.IPPROTO_TCP) return null
            }
            else -> return null
        }

        if (original.limit() < dnsOffset + 12) return null

        val destPort = when (version) {
            4 -> {
                val p1 = original.get(dnsOffset - 2).toInt() and 0xFF
                val p2 = original.get(dnsOffset - 1).toInt() and 0xFF
                ((p1 shl 8) or p2)
            }
            6 -> {
                val p1 = original.get(dnsOffset + 2).toInt() and 0xFF
                val p2 = original.get(dnsOffset + 3).toInt() and 0xFF
                ((p1 shl 8) or p2)
            }
            else -> return null
        }

        if (destPort != 53 && destPort != 853) return null

        val name = readDnsQuestionName(original, dnsOffset + 8)
        if (name == null || name.isBlank()) return null

        return DnsPacket(queryDomain = name, isResponse = false)
    }

    private fun readDnsQuestionName(packet: ByteBuffer, startOffset: Int): String? {
        try {
            val duplicate = packet.duplicate()
            duplicate.position(startOffset)
            val labels = mutableListOf<String>()
            var position = startOffset
            var jumped = false
            var pointer = startOffset

            while (position < packet.limit()) {
                val length = duplicate.get(position).toInt() and 0xFF
                if (length == 0) {
                    position++
                    break
                }

                if ((length and 0xC0) == 0xC0) {
                    if (position + 1 >= packet.limit()) return null
                    val pointerByte = duplicate.get(position + 1).toInt() and 0xFF
                    val pointerValue = ((length and 0x3F) shl 8) or pointerByte
                    if (!jumped) {
                        pointer = position + 2
                        jumped = true
                    }
                    position = pointerValue
                    continue
                }

                position++
                if (position + length > packet.limit()) return null
                val label = ByteArray(length)
                val labelBuffer = duplicate.duplicate()
                labelBuffer.position(position)
                labelBuffer.get(label)
                labels.add(String(label, Charsets.US_ASCII))
                position += length
            }

            if (labels.isEmpty()) return null
            val result = labels.joinToString(".").lowercase(Locale.US)
            if (!jumped) {
                return result
            }
            return result
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Delegates to [com.guardianlink.common.util.DnsResponseBuilder], which
     * correctly swaps IP addresses and UDP ports and recomputes checksums —
     * unlike the previous version of this function, which only flipped DNS
     * header flag bytes and wrote the same bytes back unmodified. Packets
     * built that way are not deliverable: written to a VpnService TUN as
     * "inbound" traffic, they still carry the original (device -> server)
     * addressing, so the local IP stack has no path to the waiting socket,
     * and any checksum validation along the way would drop them anyway.
     *
     * Handles both IPv4 and IPv6 (see startVpn()'s IPv6 route, added
     * alongside this fix so IPv6 DNS traffic is actually captured by the
     * tunnel in the first place — previously the packet parser had IPv6
     * support but the VPN interface itself only routed IPv4).
     */
    private fun buildNxDomainResponse(originalPacket: ByteBuffer, length: Int, dnsPacket: DnsPacket): ByteArray? {
        val bytes = originalPacket.array()
        val version = (bytes[0].toInt() and 0xF0) ushr 4
        return when (version) {
            4 -> com.guardianlink.common.util.DnsResponseBuilder.buildIPv4UdpNxDomainResponse(bytes, length)
            6 -> com.guardianlink.common.util.DnsResponseBuilder.buildIPv6UdpNxDomainResponse(bytes, length)
            else -> null
        }
    }

    private fun reportBlockedDomain(domain: String) {
        val prefs = SecurePreferences(this)
        val deviceId = prefs.getString(SecurePreferences.KEY_DEVICE_ID)
        if (deviceId.isBlank()) return

        FirebaseFirestore.getInstance().collection(FirebasePaths.COLLECTION_ALERTS)
            .add(
                mapOf(
                    "deviceId" to deviceId,
                    "type" to "DNS_BLOCKED_DOMAIN",
                    "title" to "Blocked domain",
                    "message" to "Attempted to resolve blocked domain: $domain",
                    "severity" to "MEDIUM",
                    "metadata" to mapOf("domain" to domain),
                    "isRead" to false,
                    "timestamp" to com.google.firebase.Timestamp.now()
                )
            )
    }

    private data class DnsPacket(
        val queryDomain: String?,
        val isResponse: Boolean
    )
}
