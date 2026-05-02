package com.ncmdump.i18n

import android.content.Context

/**
 * Singleton entry point for translations.
 * Set the translator implementation once during Application.onCreate().
 */
object TranslationService {

    private var _translator: Translator? = null

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
}

/** Top-level convenience function for use in composables. */
fun tr(key: String, vararg args: Any?): String = TranslationService.tr(key, *args)

