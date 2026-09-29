package com.guardianlink.common.util

object DnsFilterPolicy {
    fun normalizeDomain(domain: String?): String? {
        val cleaned = domain?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        return cleaned.takeIf { it.isNotEmpty() && it != "." }
    }

    fun isAllowed(
        domain: String?,
        allowlist: Collection<String> = emptySet(),
        denylist: Collection<String> = emptySet(),
        wildcard: Boolean = true
    ): Boolean {
        val normalized = normalizeDomain(domain) ?: return false

        if (allowlist.isNotEmpty() && allowlist.any { matchesPattern(normalized, it, wildcard) }) {
            return true
        }

        if (denylist.isNotEmpty() && denylist.any { matchesPattern(normalized, it, wildcard) }) {
            return false
        }

        return true
    }

    fun isBlocked(
        domain: String?,
        allowlist: Collection<String> = emptySet(),
        denylist: Collection<String> = emptySet(),
        wildcard: Boolean = true
    ): Boolean = !isAllowed(domain, allowlist, denylist, wildcard)

    private fun matchesPattern(domain: String, pattern: String, wildcard: Boolean): Boolean {
        val normalizedPattern = normalizeDomain(pattern) ?: return false

        if (normalizedPattern == "*") return true

        if (wildcard && normalizedPattern.startsWith("*.")) {
            val suffix = normalizedPattern.removePrefix("*.")
            return suffix.isNotBlank() && (domain == suffix || domain.endsWith(".$suffix"))
        }

        if (wildcard && normalizedPattern.endsWith(".*")) {
            val prefix = normalizedPattern.removeSuffix(".*")
            return prefix.isNotBlank() && (domain == prefix || domain.startsWith("$prefix."))
        }

        return domain == normalizedPattern || domain.endsWith(".$normalizedPattern")
    }
}
