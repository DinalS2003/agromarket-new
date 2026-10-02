package com.example.util

data class ModerationCheckResult(
    val isBlocked: Boolean,
    val warningMessage: String? = null,
    val detectedViolations: List<String> = emptyList()
)

object ChatModerator {

    // Regex for phone numbers (covers Sri Lankan mobile/landline numbers, international +94, and separated digits)
    // Avoids false positives on simple kg/quantities (e.g. 100 kg, Rs. 250)
    private val PHONE_PATTERN = Regex(
        "(?:(?:\\+?94|0)[\\s.-]?[1-9][0-9]{1,2}[\\s.-]?[0-9]{3}[\\s.-]?[0-9]{3,4})|(?:\\b[0-9]{3}[\\s.-][0-9]{3}[\\s.-][0-9]{4}\\b)|(?:\\b07[0-9]{8}\\b)",
        RegexOption.IGNORE_CASE
    )

    // Secondary pattern to catch sneaky spaced digit sequences of 9 to 11 digits
    private val SPACED_DIGITS_PATTERN = Regex(
        "(?:\\d[\\s.-]*){9,11}"
    )

    // Regex for Web links and URLs
    private val URL_PATTERN = Regex(
        "(?:https?://|www\\.)\\S+|\\b[a-zA-Z0-9-]+\\.(?:com|lk|org|net|me|io|co|app|xyz|site|online|edu|gov)\\b|\\b[a-zA-Z0-9-]+\\s+dot\\s+(?:com|lk|org|net)\\b",
        RegexOption.IGNORE_CASE
    )

    // Regex for Social media handles and platforms
    private val SOCIAL_PATTERN = Regex(
        "\\b(?:whatsapp|viber|telegram|imo|messenger|facebook|fb\\.com|fb\\.me|instagram|insta|ig|tiktok|wa\\.me|t\\.me)\\b",
        RegexOption.IGNORE_CASE
    )

    // Regex for Street and physical addresses
    private val ADDRESS_PATTERN = Regex(
        "(?:\\bno\\.?\\s*\\d+[a-zA-Z]?\\b)|" +
        "(?:\\b\\d+[/A-Za-z0-9-]*\\s+(?:[a-zA-Z]+\\s+)?(?:road|rd\\.?|street|st\\.?|lane|ln\\.?|mawatha|mw\\.?|avenue|ave\\.?|place)\\b)|" +
        "(?:\\b(?:road|rd\\.?|street|st\\.?|lane|ln\\.?|mawatha|mw\\.?|avenue|ave\\.?|place)\\s+(?:no\\.?\\s*)?\\d+\\b)|" +
        "(?:\\b(?:postal|zip)\\s*code\\s*\\d{5}\\b)",
        RegexOption.IGNORE_CASE
    )

    fun checkMessage(body: String): ModerationCheckResult {
        val trimmed = body.trim()
        if (trimmed.isBlank()) {
            return ModerationCheckResult(isBlocked = false)
        }

        // Sanitize legitimate agricultural terms (e.g. "100 kg", "Rs. 1,250", "at 250/kg", "5 pm")
        // before running digit/phone checks to prevent false alarms on trade discussions
        val tradeSanitized = trimmed.lowercase()
            .replace(Regex("(?:rs\\.?|lkr|rupees)\\s*[0-9,]+(?:\\.[0-9]{1,2})?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[0-9,]+(?:\\.[0-9]{1,2})?\\s*(?:rs\\.?|lkr|rupees)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|kilo|kilos|g|grams|ton|tons|mt|acre|acres|bundles|packs|boxes)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bat\\s*\\d{1,4}(?:/kg|\\s*per\\s*kg)?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b\\d+(?:/|\\s*per\\s*)(?:kg|kilo|g)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b\\d+%", RegexOption.IGNORE_CASE), " ")

        val violations = mutableListOf<String>()

        if (PHONE_PATTERN.containsMatchIn(tradeSanitized) || hasSuspiciousDigitSequence(tradeSanitized)) {
            violations.add("Phone numbers")
        }

        if (URL_PATTERN.containsMatchIn(trimmed)) {
            violations.add("Web links")
        }

        if (SOCIAL_PATTERN.containsMatchIn(trimmed)) {
            violations.add("Social media names")
        }

        if (ADDRESS_PATTERN.containsMatchIn(trimmed)) {
            violations.add("Street addresses")
        }

        if (violations.isNotEmpty()) {
            val listText = violations.joinToString(", ")
            val warning = "Message blocked: Contains $listText. For user safety and platform security, exchanging off-platform contacts or street addresses in chat is restricted."
            return ModerationCheckResult(
                isBlocked = true,
                warningMessage = warning,
                detectedViolations = violations
            )
        }

        return ModerationCheckResult(isBlocked = false)
    }

    private fun hasSuspiciousDigitSequence(text: String): Boolean {
        val matches = SPACED_DIGITS_PATTERN.findAll(text)
        for (match in matches) {
            val digitsOnly = match.value.filter { it.isDigit() }
            if (digitsOnly.length in 9..11) {
                return true
            }
        }
        return false
    }
}
