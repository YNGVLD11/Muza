package com.muza.app.util

object UrlExtractor {
    // Regex matching any YouTube URL with http/https
    private val YOUTUBE_URL_REGEX = Regex(
        """https?://(?:[a-zA-Z0-9_-]+\.)?youtu(?:be\.com|\.be)/[^\s"'>)]+""",
        RegexOption.IGNORE_CASE
    )

    // Regex matching 11-char video ID across YouTube URL formats
    private val VIDEO_ID_REGEX = Regex(
        """(?:youtu\.be/|/v/|/embed/|/shorts/|/live/|(?:[?&]v=))([a-zA-Z0-9_-]{11})""",
        RegexOption.IGNORE_CASE
    )

    // Regex matching URLs without scheme
    private val BARE_YOUTUBE_REGEX = Regex(
        """(?:[a-zA-Z0-9_-]+\.)?youtu(?:be\.com|\.be)/[^\s"'>)]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts and normalizes all YouTube URLs found in the text.
     */
    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        val results = mutableListOf<String>()

        // 1. Matches with scheme
        YOUTUBE_URL_REGEX.findAll(text).forEach { match ->
            val clean = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
            val idMatch = VIDEO_ID_REGEX.find(clean)
            val normalized = if (idMatch != null && idMatch.groupValues.size > 1) {
                "https://www.youtube.com/watch?v=${idMatch.groupValues[1]}"
            } else {
                clean
            }
            results.add(normalized)
        }

        // 2. If nothing found with scheme, look for bare URLs
        if (results.isEmpty()) {
            BARE_YOUTUBE_REGEX.findAll(text).forEach { match ->
                val withScheme = "https://${match.value}"
                val clean = withScheme.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')
                val idMatch = VIDEO_ID_REGEX.find(clean)
                val normalized = if (idMatch != null && idMatch.groupValues.size > 1) {
                    "https://www.youtube.com/watch?v=${idMatch.groupValues[1]}"
                } else {
                    clean
                }
                results.add(normalized)
            }
        }

        return results.distinct()
    }
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val rawUrl = YOUTUBE_URL_REGEX.find(text)?.value
            ?: BARE_YOUTUBE_REGEX.find(text)?.let { "https://${it.value}" }
            ?: return null

        // Strip trailing punctuation common in shared text (periods, quotes, commas, brackets)
        val cleanUrl = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')

        // Extract 11-character video ID if present
        val idMatch = VIDEO_ID_REGEX.find(cleanUrl)
        return if (idMatch != null && idMatch.groupValues.size > 1) {
            val videoId = idMatch.groupValues[1]
            "https://www.youtube.com/watch?v=$videoId"
        } else {
            cleanUrl
        }
    }
}
