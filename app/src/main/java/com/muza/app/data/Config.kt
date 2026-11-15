package com.muza.app.data

object Config {
    const val GITHUB_REPO_OWNER = "MuzaApp"
    const val GITHUB_REPO_NAME = "Muza"
    const val GITHUB_RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    const val GITHUB_RELEASES_PAGE_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases"

    /**
     * Chrome Mobile User-Agent without the WebView identifier ("; wv")
     * to prevent Google Authentication from blocking the embedded browser.
     */
    const val CHROME_MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36"

    const val DEFAULT_BITRATE = "320"
    val BITRATE_OPTIONS = listOf("320", "256", "192", "128")
}
