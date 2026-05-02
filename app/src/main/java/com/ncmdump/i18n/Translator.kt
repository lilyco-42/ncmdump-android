package com.ncmdump.i18n

/**
 * Modular translation interface.
 * Implementations can load translations from JSON, remote APIs, or any other source.
 */
interface Translator {

    /**
     * Translate a key with optional format arguments.
     * Returns the translated string, or the key itself if not found.
     */
    fun translate(key: String, vararg args: Any?): String

    /**
     * Get the current language code (e.g., "zh", "en").
     */
    val languageCode: String

    /**
     * Set the language by code.
     * Returns true if the language was set successfully.
     */
    fun setLanguage(code: String): Boolean

    /**
     * Get all available language codes.
     */
    fun availableLanguages(): List<String>
}
