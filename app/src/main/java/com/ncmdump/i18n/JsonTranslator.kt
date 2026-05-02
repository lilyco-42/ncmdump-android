package com.ncmdump.i18n

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * JSON file-based Translator.
 *
 * Loads translations from:
 * 1. Built-in: assets/translations/{code}.json
 * 2. Custom:  {externalDir}/{code}.json  (takes priority)
 *
 * Custom translations can be imported by the user to add new languages
 * or override built-in translations.
 */
class JsonTranslator(
    private val context: Context,
    private val assetDir: String = "translations",
    override var languageCode: String = "zh",
) : Translator {

    private var cache: MutableMap<String, JSONObject> = mutableMapOf()
    private var currentTranslations: JSONObject = JSONObject()

    /** Directory for custom translation files imported by the user. */
    val customDir: File
        get() = File(context.filesDir, "translations").also { it.mkdirs() }

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
        val codes = mutableSetOf<String>()

        // Built-in translations from assets
        try {
            context.assets.list(assetDir)
                ?.filter { it.endsWith(".json") }
                ?.mapTo(codes) { it.removeSuffix(".json") }
        } catch (_: Exception) {}

        // Custom translations from external files
        val customFiles = customDir.listFiles { f -> f.name.endsWith(".json") }
        if (customFiles != null) {
            customFiles.mapTo(codes) { it.name.removeSuffix(".json") }
        }

        return codes.sorted()
    }

    /**
     * Load a custom translation file from a Uri (imported by user).
     * Copies the file into the custom translations directory.
     * Returns the language code (file name without .json) on success, null on failure.
     */
    fun importFromUri(uri: android.net.Uri): String? {
        return try {
            val fileName = getFileName(uri) ?: return null
            val code = fileName.removeSuffix(".json")
            if (code == fileName) return null // not a .json file

            val destFile = File(customDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            code
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generate a translation template JSON with all keys from built-in translations.
     * The user can edit this file to create a custom translation.
     */
    fun generateTemplate(outputFile: File): Boolean {
        return try {
            val allKeys = mutableSetOf<String>()

            // Collect all keys from all built-in translation files
            try {
                context.assets.list(assetDir)
                    ?.filter { it.endsWith(".json") }
                    ?.forEach { fileName ->
                        val inputStream = context.assets.open("$assetDir/$fileName")
                        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
                        val text = reader.readText()
                        reader.close()
                        val json = JSONObject(text)
                        collectKeys("", json, allKeys)
                    }
            } catch (_: Exception) {}

            // Build template JSON with empty values
            val template = JSONObject()
            for (key in allKeys.sorted()) {
                template.put(key, "")
            }

            outputFile.writeText(template.toString(2), StandardCharsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Collect dot-separated keys from a nested JSON object. */
    private fun collectKeys(prefix: String, obj: JSONObject, keys: MutableSet<String>) {
        for (key in obj.keys()) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = obj.get(key)
            when (value) {
                is JSONObject -> collectKeys(fullKey, value, keys)
                else -> keys.add(fullKey)
            }
        }
    }

    /**
     * Get the display name of a language code (returns the translation
     * of "lang.{code}" from the currently loaded translations, or the code itself).
     */
    fun getLanguageDisplayName(code: String): String {
        return try {
            val parts = listOf("lang", code)
            var current: Any? = currentTranslations
            for (part in parts) {
                when (current) {
                    is JSONObject -> current = current.opt(part)
                    else -> return code
                }
            }
            current?.toString() ?: code
        } catch (_: Exception) {
            code
        }
    }

    private fun loadLanguage(code: String) {
        val customFile = File(customDir, "$code.json")
        val builtInJson = loadBuiltIn(code)

        if (customFile.exists()) {
            try {
                val customText = customFile.readText(StandardCharsets.UTF_8)
                val customJson = JSONObject(customText)
                // Merge: custom overrides built-in
                currentTranslations = deepMerge(builtInJson, customJson)
                return
            } catch (_: Exception) {}
        }

        currentTranslations = builtInJson
    }

    private fun loadBuiltIn(code: String): JSONObject {
        val fileName = "$code.json"
        val path = "$assetDir/$fileName"
        return try {
            val inputStream = context.assets.open(path)
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val text = reader.readText()
            reader.close()
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    /** Deep merge two JSONObjects: source is base, override takes priority. */
    private fun deepMerge(source: JSONObject, override: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in source.keys()) {
            val sVal = source.get(key)
            val oVal = override.opt(key)
            if (oVal != null && sVal is JSONObject && oVal is JSONObject) {
                result.put(key, deepMerge(sVal, oVal))
            } else {
                result.put(key, oVal ?: sVal)
            }
        }
        for (key in override.keys()) {
            if (!result.has(key)) {
                result.put(key, override.get(key))
            }
        }
        return result
    }

    private fun getFileName(uri: android.net.Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) it.getString(idx) else null
            } else null
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
