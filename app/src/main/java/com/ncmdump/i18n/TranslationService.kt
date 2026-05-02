package com.ncmdump.i18n

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Singleton entry point for translations.
 * Set the translator implementation once during Application.onCreate().
 */
object TranslationService {

    private var _translator: Translator? = null

    private val jsonTranslator: JsonTranslator
        get() = _translator as? JsonTranslator
            ?: throw IllegalStateException("TranslationService not initialized or not using JsonTranslator")

    val translator: Translator
        get() = _translator ?: throw IllegalStateException(
            "TranslationService not initialized. Call init(context) first."
        )

    fun init(context: Context, languageCode: String = "zh") {
        _translator = JsonTranslator(
            context = context.applicationContext,
            languageCode = languageCode,
        )
    }

    fun setTranslator(impl: Translator) {
        _translator = impl
    }

    fun tr(key: String, vararg args: Any?): String {
        return translator.translate(key, *args)
    }

    /**
     * Generate a translation template JSON file with all known keys.
     * Returns the output file path on success, null on failure.
     */
    fun generateTemplate(context: Context, outputFile: File): Boolean {
        return jsonTranslator.generateTemplate(outputFile)
    }

    /**
     * Import a custom translation from a content URI.
     * Returns the language code on success, null on failure.
     */
    fun importTranslation(uri: Uri): String? {
        return jsonTranslator.importFromUri(uri)
    }

    /**
     * Get the custom translations directory.
     */
    fun getCustomTranslationsDir(): File {
        return jsonTranslator.customDir
    }
}

/** Top-level convenience function for use in composables. */
fun tr(key: String, vararg args: Any?): String = TranslationService.tr(key, *args)
