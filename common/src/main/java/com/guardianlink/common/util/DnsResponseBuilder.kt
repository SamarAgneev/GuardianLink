package com.guardianlink.common.util

/**
 * Pure, Android-independent construction of a synthetic NXDOMAIN DNS response,
 * given the raw bytes of an inbound IPv4/UDP DNS query packet as captured from
 * a [android.net.VpnService] TUN interface.
 *
 * ## Why this exists
 * Packets written to a `VpnService` TUN's output stream are injected back into
 * the device as *inbound* traffic. For the querying app's socket to actually
 * receive this as "the DNS server answered", the response packet must, at a
 * minimum:
 *  - swap the IPv4 source/destination addresses (so it appears to come from
 *    the queried DNS server back to the device, not from the device to itself)
 *  - swap the UDP source/destination ports
 *  - carry a correctly recomputed IPv4 header checksum
 *  - carry a correctly recomputed UDP checksum (RFC 768/791) — many network
 *    stacks validate this and will silently drop a packet with a wrong one
 *  - preserve the original 16-bit DNS transaction ID unchanged, so the
 *    caller's resolver matches the reply to its pending query
 *  - preserve the original question section (echoed back, unmodified)
 *  - set QR=1 (this is a response), RCODE=3 (NXDOMAIN), and zero the
 *    answer/authority/additional record counts (an NXDOMAIN carries none)
 *
 * A previous implementation only flipped the DNS header's flag bytes and
 * wrote the same bytes back unmodified — it never swapped addresses/ports
 * and never recomputed either checksum, so the resulting bytes were not a
 * deliverable IPv4/UDP packet: the local IP stack has no reason to route it
 * to the waiting socket, and any checksum validation along the way would
 * cause it to be silently dropped. This class replaces that logic with a
 * byte-for-byte correct transform, verified against RFC 1071's checksum
 * validation identity (see [DnsResponseBuilderTest]).
 *
 * ## Scope
 * Only IPv4-over-UDP DNS queries are handled. IPv6 and TCP-framed DNS are
 * intentionally out of scope for this class — see
 * `ContentFilterVpnService`'s IPv6 handling notes for why IPv6 is currently a
 * known, disclosed gap rather than silently "handled" here. Callers should
 * treat a `null` return as "forward the original packet unmodified" (which
 * will simply time out for a blocked domain) rather than as an error.
 */
object DnsResponseBuilder {

    private const val IPV4_VERSION = 4
    private const val IPV4_HEADER_MIN_LENGTH = 20
    private const val UDP_HEADER_LENGTH = 8
    private const val DNS_HEADER_LENGTH = 12
    private const val PROTOCOL_UDP = 17

    /**
     * Builds a synthetic NXDOMAIN response for the given raw IPv4/UDP packet.
     *
     * @param packet the raw packet bytes exactly as read from the TUN
     *   interface (may be larger than [length]; only the first [length]
     *   bytes are considered part of the packet).
     * @param length the actual packet length within [packet].
     * @return the response packet bytes ready to write back to the TUN
     *   interface's output stream, or `null` if [packet] is not a
     *   well-formed IPv4/UDP packet containing at least a 12-byte DNS
     *   header (malformed/truncated input, non-IPv4, or non-UDP).
     */
    fun buildIPv4UdpNxDomainResponse(packet: ByteArray, length: Int): ByteArray? {
        if (length < IPV4_HEADER_MIN_LENGTH || length > packet.size) return null
        if (((packet[0].toInt() and 0xF0) ushr 4) != IPV4_VERSION) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER_MIN_LENGTH || length < ihl + UDP_HEADER_LENGTH) return null
        if ((packet[9].toInt() and 0xFF) != PROTOCOL_UDP) return null

        val udpOffset = ihl
        val udpLength = readUInt16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || ihl + udpLength > length) return null

        val dnsOffset = udpOffset + UDP_HEADER_LENGTH
        val dnsLength = udpLength - UDP_HEADER_LENGTH
        if (dnsLength < DNS_HEADER_LENGTH) return null

        val totalLength = ihl + udpLength
        val response = packet.copyOf(totalLength)

        swapIpAddresses(response)
        recomputeIpChecksum(response, ihl)
        swapUdpPorts(response, udpOffset)
        rewriteDnsHeaderAsNxDomain(response, dnsOffset)
        recomputeUdpChecksum(response, ihl, udpOffset, udpLength)

        return response
    }

    private fun swapIpAddresses(data: ByteArray) {
        for (i in 0 until 4) {
            val tmp = data[12 + i]
            data[12 + i] = data[16 + i]
            data[16 + i] = tmp
        }
    }

    private fun recomputeIpChecksum(data: ByteArray, ihl: Int) {
        data[10] = 0
        data[11] = 0
        val checksum = internetChecksum(data, 0, ihl)
        data[10] = (checksum ushr 8).toByte()
        data[11] = (checksum and 0xFF).toByte()
    }

    private fun swapUdpPorts(data: ByteArray, udpOffset: Int) {
        for (i in 0 until 2) {
            val tmp = data[udpOffset + i]
            data[udpOffset + i] = data[udpOffset + 2 + i]
            data[udpOffset + 2 + i] = tmp
        }
    }

    private fun rewriteDnsHeaderAsNxDomain(data: ByteArray, dnsOffset: Int) {
        // Transaction ID (dnsOffset, dnsOffset+1) is left untouched.
        // Byte 2: set QR=1 (response); keep OPCODE/AA/TC/RD exactly as the
        // resolver sent them (in particular RD, which a well-behaved
        // response echoes back).
        data[dnsOffset + 2] = (data[dnsOffset + 2].toInt() or 0x80).toByte()
        // Byte 3: RA=1, Z=0, RCODE=3 (NXDOMAIN).
        data[dnsOffset + 3] = 0x83.toByte()
        // QDCOUNT (dnsOffset+4, +5) unchanged — the question section is echoed.
        // ANCOUNT / NSCOUNT / ARCOUNT = 0 (an NXDOMAIN carries no records).
        for (offset in intArrayOf(6, 7, 8, 9, 10, 11)) {
            data[dnsOffset + offset] = 0
        }
    }

    private fun recomputeUdpChecksum(data: ByteArray, ihl: Int, udpOffset: Int, udpLength: Int) {
        data[udpOffset + 6] = 0
        data[udpOffset + 7] = 0
        val checksum = udpChecksumWithPseudoHeader(data, udpOffset, udpLength)
        // Per RFC 768: a computed UDP checksum of exactly 0x0000 must be sent
        // as 0xFFFF, since 0x0000 on the wire means "no checksum was computed".
        val finalChecksum = if (checksum == 0) 0xFFFF else checksum
        data[udpOffset + 6] = (finalChecksum ushr 8).toByte()
        data[udpOffset + 7] = (finalChecksum and 0xFF).toByte()
    }

    private const val IPV6_VERSION = 6
    private const val IPV6_HEADER_LENGTH = 40

    /**
     * IPv6 equivalent of [buildIPv4UdpNxDomainResponse]. IPv6 has no header
     * checksum (unlike IPv4), but the UDP checksum is *mandatory* — a value
     * of 0x0000 is not a legal "no checksum" marker in IPv6 the way it is in
     * IPv4/UDP, so the same "0x0000 -> 0xFFFF" substitution is applied here
     * too, for the same reason (never emit a literal zero checksum).
     *
     * Only the simple case of no IPv6 extension headers between the fixed
     * 40-byte header and the UDP header is handled (Next Header == UDP
     * directly), which is the overwhelmingly common case for plain DNS
     * traffic. A packet with extension headers is not parsed as DNS in the
     * first place by the caller's packet parser, so this is not reachable
     * with unexpected input in practice — it returns `null` defensively if
     * it is.
     */
    fun buildIPv6UdpNxDomainResponse(packet: ByteArray, length: Int): ByteArray? {
        if (length < IPV6_HEADER_LENGTH + UDP_HEADER_LENGTH) return null
        if (((packet[0].toInt() and 0xF0) ushr 4) != IPV6_VERSION) return null
        if ((packet[6].toInt() and 0xFF) != PROTOCOL_UDP) return null // Next Header

        val udpOffset = IPV6_HEADER_LENGTH
        val udpLength = readUInt16(packet, udpOffset + 4)
        if (udpLength < UDP_HEADER_LENGTH || udpOffset + udpLength > length) return null

        val dnsOffset = udpOffset + UDP_HEADER_LENGTH
        val dnsLength = udpLength - UDP_HEADER_LENGTH
        if (dnsLength < DNS_HEADER_LENGTH) return null

        val totalLength = udpOffset + udpLength
        val response = packet.copyOf(totalLength)

        swapIpv6Addresses(response)
        swapUdpPorts(response, udpOffset)
        rewriteDnsHeaderAsNxDomain(response, dnsOffset)
        recomputeIpv6UdpChecksum(response, udpOffset, udpLength)

        return response
    }

    private fun swapIpv6Addresses(data: ByteArray) {
        for (i in 0 until 16) {
            val tmp = data[8 + i]
            data[8 + i] = data[24 + i]
            data[24 + i] = tmp
        }
    }

    private fun recomputeIpv6UdpChecksum(data: ByteArray, udpOffset: Int, udpLength: Int) {
        data[udpOffset + 6] = 0
        data[udpOffset + 7] = 0
        val checksum = ipv6UdpChecksumWithPseudoHeader(data, udpOffset, udpLength)
        val finalChecksum = if (checksum == 0) 0xFFFF else checksum
        data[udpOffset + 6] = (finalChecksum ushr 8).toByte()
        data[udpOffset + 7] = (finalChecksum and 0xFF).toByte()
    }

    /** IPv6 pseudo-header per RFC 2460 §8.1: src(16) + dst(16) + upper-layer length(4) + zero(3) + next header(1). */
    private fun ipv6UdpChecksumWithPseudoHeader(data: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += ((data[8 + i].toInt() and 0xFF) shl 8) or (data[8 + i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 16 step 2) {
            sum += ((data[24 + i].toInt() and 0xFF) shl 8) or (data[24 + i + 1].toInt() and 0xFF)
        }
        // Upper-layer packet length as a 32-bit field, summed as two 16-bit words.
        sum += (udpLength.toLong() ushr 16) and 0xFFFF
        sum += udpLength.toLong() and 0xFFFF
        // 3 zero bytes + next header (UDP=17) as one 16-bit word (zero high byte + 17).
        sum += PROTOCOL_UDP.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /** RFC 1071 one's-complement checksum over `data[start, start+length)`. */
    private fun internetChecksum(data: ByteArray, start: Int, length: Int): Int {
        var sum = 0L
        var i = start
        val end = start + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum over the IPv4 pseudo-header + UDP header + payload (RFC 768/791). */
    private fun udpChecksumWithPseudoHeader(data: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        // Pseudo-header: source address (4 bytes, at offset 12) + destination
        // address (4 bytes, at offset 16) — using the addresses as they stand
        // in `data` at call time (i.e. already swapped by swapIpAddresses).
        for (i in 0 until 4 step 2) {
            sum += ((data[12 + i].toInt() and 0xFF) shl 8) or (data[12 + i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 4 step 2) {
            sum += ((data[16 + i].toInt() and 0xFF) shl 8) or (data[16 + i + 1].toInt() and 0xFF)
        }
        // Pseudo-header zero byte + protocol byte (UDP = 17), as one 16-bit word.
        sum += PROTOCOL_UDP.toLong()
        // Pseudo-header UDP length field.
        sum += udpLength.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }
}
