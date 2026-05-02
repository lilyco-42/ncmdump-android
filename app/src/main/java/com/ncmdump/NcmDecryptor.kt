package com.ncmdump

import org.json.JSONObject

/**
 * JNI bridge to the native ncmcore library.
 * Handles .ncm file decryption.
 */
object NcmDecryptor {

    init {
        System.loadLibrary("ncmcore")
    }

    /**
     * Decrypt a .ncm file.
     *
     * @param inputPath  Absolute path to the .ncm file
     * @param outputDir  Directory path where output will be written
     * @return JSON string with fields:
     *   - success: boolean
     *   - outputPath: string (only if success)
     *   - error: string (only if !success)
     *   - metadata: object (only if success)
     *   - coverArtPath: string (only if success and cover art exists)
     */
    private external fun decryptFile(inputPath: String, outputDir: String): String

    data class DecryptResult(
        val success: Boolean,
        val outputPath: String? = null,
        val error: String? = null,
        val coverArtPath: String? = null,
        val metadata: MusicMetadata? = null,
    )

    data class MusicMetadata(
        val name: String,
        val artist: String,
        val album: String,
        val format: String,
        val duration: Int,
        val bitrate: Int,
    )

    fun decrypt(inputPath: String, outputDir: String): DecryptResult {
        return try {
            val json = decryptFile(inputPath, outputDir)
            val obj = JSONObject(json)

            if (!obj.optBoolean("success", false)) {
                return DecryptResult(
                    success = false,
                    error = obj.optString("error", "Unknown error")
                )
            }

            val metadataObj = obj.optJSONObject("metadata")
            val metadata = if (metadataObj != null && metadataObj.length() > 0) {
                MusicMetadata(
                    name = metadataObj.optString("name", ""),
                    artist = metadataObj.optString("artist", ""),
                    album = metadataObj.optString("album", ""),
                    format = metadataObj.optString("format", ""),
                    duration = metadataObj.optInt("duration", 0),
                    bitrate = metadataObj.optInt("bitrate", 0),
                )
            } else null

            DecryptResult(
                success = true,
                outputPath = obj.optString("outputPath"),
                coverArtPath = obj.optString("coverArtPath", null),
                metadata = metadata,
            )
        } catch (e: Exception) {
            DecryptResult(success = false, error = e.message ?: "Unknown error")
        }
    }
}
