package com.guardianlink.common.util

import java.security.MessageDigest
import java.util.Locale

object CommunicationUtils {
    fun normalizeText(value: String?): String = value.orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun findKeywordMatches(text: String?, configuredKeywords: List<String>): List<String> {
        val normalizedText = normalizeText(text)
        if (normalizedText.isBlank()) return emptyList()

        return configuredKeywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeText(it) }
            .filter { keyword ->
                val normalizedKeyword = normalizeText(keyword)
                normalizedKeyword.isNotBlank() &&
                    Regex(
                        "(?<![\\p{L}\\p{N}])${Regex.escape(normalizedKeyword)}(?![\\p{L}\\p{N}])"
                    ).containsMatchIn(normalizedText)
            }
    }

    fun normalizePhoneNumber(value: String?, defaultCountryCode: String = ""): String {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return ""
        val hasPlus = raw.startsWith("+")
        var digits = raw.filter(Char::isDigit)
        if (digits.startsWith("00")) {
            digits = digits.drop(2)
            return if (digits.isBlank()) "" else "+$digits"
        }
        if (digits.isBlank()) return ""
        if (hasPlus) return "+$digits"

        val country = defaultCountryCode.filter(Char::isDigit)
        if (country.isNotBlank() && digits.startsWith("0")) {
            return "+$country${digits.drop(1)}"
        }
        return digits
    }

    fun phoneNumbersMatch(first: String?, second: String?, defaultCountryCode: String = ""): Boolean {
        val left = normalizePhoneNumber(first, defaultCountryCode)
        val right = normalizePhoneNumber(second, defaultCountryCode)
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true

        val leftDigits = left.removePrefix("+")
        val rightDigits = right.removePrefix("+")
        return when {
            leftDigits.length == 11 && leftDigits.startsWith("1") && leftDigits.drop(1) == rightDigits -> true
            rightDigits.length == 11 && rightDigits.startsWith("1") && rightDigits.drop(1) == leftDigits -> true
            else -> false
        }
    }

    fun stableRecordId(prefix: String, vararg parts: String): String {
        val source = parts.joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "${prefix}_$digest"
    }
}