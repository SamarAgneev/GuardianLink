package com.guardianlink.common

import com.guardianlink.common.util.DnsResponseBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests build a real, checksum-valid synthetic IPv4/UDP DNS query
 * packet by hand and verify the transformed response against the RFC 1071
 * checksum-validation identity (summing every 16-bit word of a header,
 * *including* its own checksum field, folds to 0xFFFF iff the checksum is
 * correct) rather than trusting the production code's own checksum
 * arithmetic to grade itself.
 */
class DnsResponseBuilderTest {

    private val srcIp = byteArrayOf(10, 0, 0, 2)
    private val dstIp = byteArrayOf(8, 8, 8, 8)
    private val srcPort = 54321
    private val dstPort = 53
    private val transactionId = 0xABCD

    /** Builds a well-formed, checksum-valid IPv4/UDP DNS query for "www.example.com". */
    private fun buildQueryPacket(): ByteArray {
        val qname = encodeQName("www.example.com")
        val question = qname + byteArrayOf(0, 1, 0, 1) // QTYPE=A, QCLASS=IN
        val dnsHeader = byteArrayOf(
            (transactionId ushr 8).toByte(), (transactionId and 0xFF).toByte(),
            0x01, 0x00, // flags: RD=1
            0x00, 0x01, // QDCOUNT=1
            0x00, 0x00, // ANCOUNT
            0x00, 0x00, // NSCOUNT
            0x00, 0x00  // ARCOUNT
        )
        val dns = dnsHeader + question

        val udpLength = 8 + dns.size
        val pseudoAndUdpZeroChecksum = srcIp + dstIp + byteArrayOf(0, 17) +
            be16(udpLength) + be16(srcPort) + be16(dstPort) + be16(udpLength) + byteArrayOf(0, 0) + dns
        val udpChecksum = checksumRef(pseudoAndUdpZeroChecksum)
        val udpHeader = be16(srcPort) + be16(dstPort) + be16(udpLength) + be16(udpChecksum)

        val ihl = 5
        val totalLength = ihl * 4 + udpLength
        val ipHeaderZero = byteArrayOf(
            ((4 shl 4) or ihl).toByte(), 0
        ) + be16(totalLength) + be16(0x1234) + be16(0) +
            byteArrayOf(64, 17) + be16(0) + srcIp + dstIp
        val ipChecksum = checksumRef(ipHeaderZero)
        val ipHeader = byteArrayOf(
            ((4 shl 4) or ihl).toByte(), 0
        ) + be16(totalLength) + be16(0x1234) + be16(0) +
            byteArrayOf(64, 17) + be16(ipChecksum) + srcIp + dstIp

        return ipHeader + udpHeader + dns
    }

    private fun encodeQName(domain: String): ByteArray {
        val out = mutableListOf<Byte>()
        domain.split(".").forEach { label ->
            out.add(label.length.toByte())
            out.addAll(label.toByteArray(Charsets.US_ASCII).toList())
        }
        out.add(0)
        return out.toByteArray()
    }

    private fun be16(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), (value and 0xFF).toByte())

    /** Independent reference implementation of RFC 1071 checksum, used only by the test. */
    private fun checksumRef(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Sums every 16-bit word of `data[start, start+length)` as-is (checksum field included). */
    private fun verifyChecksumFoldsToAllOnes(data: ByteArray, start: Int, length: Int): Boolean {
        var sum = 0L
        var i = start
        val end = start + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum and 0xFFFF) == 0xFFFFL
    }

    @Test
    fun ipHeaderChecksumOfResponseIsValid() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)
        assertNotNull(response)
        val ihl = (response!![0].toInt() and 0x0F) * 4
        assertTrue(verifyChecksumFoldsToAllOnes(response, 0, ihl))
    }

    @Test
    fun udpChecksumOfResponseIsValid() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)!!
        val ihl = (response[0].toInt() and 0x0F) * 4
        val udpOffset = ihl
        val udpLength = ((response[udpOffset + 4].toInt() and 0xFF) shl 8) or (response[udpOffset + 5].toInt() and 0xFF)

        // Recompute independently (pseudo-header uses the addresses as they now
        // stand in the response, i.e. already swapped) and compare to the
        // embedded checksum rather than trusting the production code.
        val embedded = ((response[udpOffset + 6].toInt() and 0xFF) shl 8) or (response[udpOffset + 7].toInt() and 0xFF)
        val zeroed = response.copyOf()
        zeroed[udpOffset + 6] = 0
        zeroed[udpOffset + 7] = 0
        val pseudoPlusUdp = zeroed.copyOfRange(12, 16) + zeroed.copyOfRange(16, 20) +
            byteArrayOf(0, 17) + be16(udpLength) + zeroed.copyOfRange(udpOffset, udpOffset + udpLength)
        var recomputed = checksumRef(pseudoPlusUdp)
        if (recomputed == 0) recomputed = 0xFFFF

        assertEquals(recomputed, embedded)
    }

    @Test
    fun addressesAreSwapped() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)!!
        assertTrue(response.copyOfRange(12, 16).contentEquals(dstIp)) // new source = original dest
        assertTrue(response.copyOfRange(16, 20).contentEquals(srcIp)) // new dest = original source
    }

    @Test
    fun portsAreSwapped() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)!!
        val ihl = (response[0].toInt() and 0x0F) * 4
        val newSrcPort = ((response[ihl].toInt() and 0xFF) shl 8) or (response[ihl + 1].toInt() and 0xFF)
        val newDstPort = ((response[ihl + 2].toInt() and 0xFF) shl 8) or (response[ihl + 3].toInt() and 0xFF)
        assertEquals(dstPort, newSrcPort)
        assertEquals(srcPort, newDstPort)
    }

    @Test
    fun transactionIdIsPreservedAndRcodeIsNxDomain() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)!!
        val ihl = (response[0].toInt() and 0x0F) * 4
        val dnsOffset = ihl + 8
        val txid = ((response[dnsOffset].toInt() and 0xFF) shl 8) or (response[dnsOffset + 1].toInt() and 0xFF)
        assertEquals(transactionId, txid)

        val qrBit = response[dnsOffset + 2].toInt() and 0x80
        assertTrue("QR bit must be set on a response", qrBit != 0)
        val rcode = response[dnsOffset + 3].toInt() and 0x0F
        assertEquals("RCODE must be 3 (NXDOMAIN)", 3, rcode)

        // ANCOUNT/NSCOUNT/ARCOUNT must all be zero.
        for (offset in intArrayOf(6, 7, 8, 9, 10, 11)) {
            assertEquals(0, response[dnsOffset + offset].toInt())
        }
    }

    @Test
    fun questionSectionIsEchoedUnchanged() {
        val query = buildQueryPacket()
        val response = DnsResponseBuilder.buildIPv4UdpNxDomainResponse(query, query.size)!!
        val queryIhl = (query[0].toInt() and 0x0F) * 4
        val queryDnsOffset = queryIhl + 8
        val responseIhl = (response[0].toInt() and 0x0F) * 4
        val responseDnsOffset = responseIhl + 8

        val queryQuestion = query.copyOfRange(queryDnsOffset + 12, query.size)
        val responseQuestion = response.copyOfRange(responseDnsOffset + 12, response.size)
        assertTrue(queryQuestion.contentEquals(responseQuestion))
    }

    @Test
    fun truncatedPacketReturnsNull() {
        val query = buildQueryPacket()
        val truncated = query.copyOf(10) // shorter than a minimal IPv4 header
        assertNull(DnsResponseBuilder.buildIPv4UdpNxDomainResponse(truncated, truncated.size))
    }

    @Test
    fun nonUdpProtocolReturnsNull() {
        val query = buildQueryPacket()
        val tcpVariant = query.copyOf()
        tcpVariant[9] = 6 // TCP instead of UDP
        assertNull(DnsResponseBuilder.buildIPv4UdpNxDomainResponse(tcpVariant, tcpVariant.size))
    }

    @Test
    fun ipv6PacketReturnsNull() {
        val query = buildQueryPacket()
        val ipv6Variant = query.copyOf()
        ipv6Variant[0] = (6 shl 4).toByte() // version 6 in the high nibble
        assertNull(DnsResponseBuilder.buildIPv4UdpNxDomainResponse(ipv6Variant, ipv6Variant.size))
    }

    // ── IPv6 ──────────────────────────────────────────────────────────────────

    private val srcIp6 = byteArrayOf(0xfd.toByte(), 0, 0, 1, 0xfd.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2)
    private val dstIp6 = byteArrayOf(0x20, 1, 0x48, 0x60, 0x48, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0x88.toByte(), 0x88.toByte())

    private fun buildIpv6QueryPacket(): ByteArray {
        val qname = encodeQName("blocked.example")
        val question = qname + byteArrayOf(0, 1, 0, 1)
        val dnsHeader = byteArrayOf(
            (transactionId ushr 8).toByte(), (transactionId and 0xFF).toByte(),
            0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val dns = dnsHeader + question
        val udpLength = 8 + dns.size

        val pseudo = srcIp6 + dstIp6 + be32(udpLength) + byteArrayOf(0, 0, 0, 17) +
            be16(srcPort) + be16(dstPort) + be16(udpLength) + byteArrayOf(0, 0) + dns
        val udpChecksum = checksumRef(pseudo)
        val udpHeader = be16(srcPort) + be16(dstPort) + be16(udpLength) + be16(udpChecksum)

        val ipv6Header = byteArrayOf((6 shl 4).toByte(), 0, 0, 0) + be16(udpLength) + byteArrayOf(17, 64) + srcIp6 + dstIp6
        return ipv6Header + udpHeader + dns
    }

    private fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(), ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
    )

    @Test
    fun ipv6UdpChecksumOfResponseIsValid() {
        val query = buildIpv6QueryPacket()
        val response = DnsResponseBuilder.buildIPv6UdpNxDomainResponse(query, query.size)
        assertNotNull(response)
        val udpOffset = 40
        val udpLength = ((response!![udpOffset + 4].toInt() and 0xFF) shl 8) or (response[udpOffset + 5].toInt() and 0xFF)
        val embedded = ((response[udpOffset + 6].toInt() and 0xFF) shl 8) or (response[udpOffset + 7].toInt() and 0xFF)

        val zeroed = response.copyOf()
        zeroed[udpOffset + 6] = 0
        zeroed[udpOffset + 7] = 0
        val pseudo = zeroed.copyOfRange(8, 24) + zeroed.copyOfRange(24, 40) +
            be32(udpLength) + byteArrayOf(0, 0, 0, 17) + zeroed.copyOfRange(udpOffset, udpOffset + udpLength)
        var recomputed = checksumRef(pseudo)
        if (recomputed == 0) recomputed = 0xFFFF

        assertEquals(recomputed, embedded)
    }

    @Test
    fun ipv6AddressesAndPortsAreSwapped() {
        val query = buildIpv6QueryPacket()
        val response = DnsResponseBuilder.buildIPv6UdpNxDomainResponse(query, query.size)!!
        assertTrue(response.copyOfRange(8, 24).contentEquals(dstIp6))
        assertTrue(response.copyOfRange(24, 40).contentEquals(srcIp6))

        val udpOffset = 40
        val newSrcPort = ((response[udpOffset].toInt() and 0xFF) shl 8) or (response[udpOffset + 1].toInt() and 0xFF)
        val newDstPort = ((response[udpOffset + 2].toInt() and 0xFF) shl 8) or (response[udpOffset + 3].toInt() and 0xFF)
        assertEquals(dstPort, newSrcPort)
        assertEquals(srcPort, newDstPort)
    }

    @Test
    fun ipv6TransactionIdPreservedAndRcodeIsNxDomain() {
        val query = buildIpv6QueryPacket()
        val response = DnsResponseBuilder.buildIPv6UdpNxDomainResponse(query, query.size)!!
        val dnsOffset = 40 + 8
        val txid = ((response[dnsOffset].toInt() and 0xFF) shl 8) or (response[dnsOffset + 1].toInt() and 0xFF)
        assertEquals(transactionId, txid)
        assertTrue((response[dnsOffset + 2].toInt() and 0x80) != 0)
        assertEquals(3, response[dnsOffset + 3].toInt() and 0x0F)
    }

    @Test
    fun ipv4PacketRejectedByIpv6Builder() {
        val query = buildQueryPacket() // IPv4
        assertNull(DnsResponseBuilder.buildIPv6UdpNxDomainResponse(query, query.size))
    }

    @Test
    fun packetShorterThanDeclaredUdpLengthReturnsNull() {
        val query = buildQueryPacket()
        // Claim a UDP length far larger than the actual remaining bytes.
        val corrupted = query.copyOf()
        val ihl = (corrupted[0].toInt() and 0x0F) * 4
        corrupted[ihl + 4] = 0x7F
        corrupted[ihl + 5] = 0xFF.toByte()
        assertNull(DnsResponseBuilder.buildIPv4UdpNxDomainResponse(corrupted, corrupted.size))
    }
}
