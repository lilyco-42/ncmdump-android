package com.ncmdump.i18n

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * JSON file-based Translator.
 *
 * Loads translations from assets/translations/{code}.json
 * Falls back to the key itself if no translation is found.
 *
 * JSON format:
 * ```json
 * {
 *   "app": {
 *     "name": "NCM Decryptor",
 *     "selectFiles": "Select NCM Files"
 *   },
 *   "button": {
 *     "decrypt": "Decrypt"
 *   }
 * }
 * ```
 *
 * Keys use dot-separated paths: "app.name", "button.decrypt"
 */
class JsonTranslator(
    private val context: Context,
    private val assetDir: String = "translations",
    override var languageCode: String = "zh",
) : Translator {

    private var cache: MutableMap<String, JSONObject> = mutableMapOf()
    private var currentTranslations: JSONObject = JSONObject()

    init {
        loadLanguage(languageCode)
    }

    override fun translate(key: String, vararg args: Any?): String {
        val parts = key.split(".")
        var current: Any? = currentTranslations

        for (part in parts) {
            when (current) {
                is JSONObject -> current = current.opt(part)
                else -> return key
            }
        }

        val template = current?.toString() ?: return key
        return if (args.isNotEmpty()) {
            formatString(template, args)
        } else {
            template
        }
    }

    override fun setLanguage(code: String): Boolean {
        return try {
            loadLanguage(code)
            languageCode = code
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun availableLanguages(): List<String> {
        return try {
            context.assets.list(assetDir)
                ?.filter { it.endsWith(".json") }
                ?.map { it.removeSuffix(".json") }
                ?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun loadLanguage(code: String) {
        val fileName = "$code.json"
        val path = "$assetDir/$fileName"

        try {
            val inputStream = context.assets.open(path)
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val text = reader.readText()
            reader.close()
            currentTranslations = JSONObject(text)
        } catch (e: Exception) {
            currentTranslations = JSONObject()
        }
    }

    private fun formatString(template: String, args: Array<out Any?>): String {
        var result = template
        for ((index, arg) in args.withIndex()) {
            result = result.replace("{$index}", arg?.toString() ?: "")
        }
        return result
    }
}
