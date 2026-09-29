package com.guardianlink.common

import com.guardianlink.common.util.DnsFilterPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsFilterPolicyTest {

    @Test
    fun denylistBlocksExactAndSubdomainMatches() {
        val denylist = setOf("example.com", "*.adult.example.com")

        assertTrue(DnsFilterPolicy.isBlocked("example.com", denylist = denylist))
        assertTrue(DnsFilterPolicy.isBlocked("www.example.com", denylist = denylist))
        assertTrue(DnsFilterPolicy.isBlocked("video.adult.example.com", denylist = denylist))
        assertFalse(DnsFilterPolicy.isBlocked("other.com", denylist = denylist))
    }

    @Test
    fun allowlistOverridesDenylist() {
        val allowlist = setOf("trusted.example.com")
        val denylist = setOf("example.com")

        assertFalse(DnsFilterPolicy.isBlocked("trusted.example.com", allowlist = allowlist, denylist = denylist))
        assertTrue(DnsFilterPolicy.isBlocked("login.example.com", allowlist = allowlist, denylist = denylist))
    }

    @Test
    fun wildcardMatchesSubdomains() {
        val denylist = setOf("*.example.com")

        assertTrue(DnsFilterPolicy.isBlocked("api.example.com", denylist = denylist))
        assertTrue(DnsFilterPolicy.isBlocked("example.com", denylist = denylist))
        assertFalse(DnsFilterPolicy.isBlocked("example.org", denylist = denylist))
    }

    @Test
    fun malformedInputIsRejectedSafely() {
        assertFalse(DnsFilterPolicy.isBlocked("", denylist = setOf("example.com")))
        assertFalse(DnsFilterPolicy.isBlocked(".", denylist = setOf("example.com")))
        assertFalse(DnsFilterPolicy.isBlocked("   ", denylist = setOf("example.com")))
        assertFalse(DnsFilterPolicy.isBlocked(null, denylist = setOf("example.com")))
    }
}
