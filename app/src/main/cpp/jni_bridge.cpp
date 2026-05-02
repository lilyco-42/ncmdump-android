#include <jni.h>
#include <string>
#include <filesystem>
#include <fstream>
#include <sstream>

#include "ncmcrypt.h"

namespace fs = std::filesystem;

static std::string jniEscapeJson(const std::string &s)
{
    std::string out;
    out.reserve(s.size());
    for (char c : s)
    {
        switch (c)
        {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c;
        }
    }
    return out;
}

extern "C" {

/**
 * Decrypt a single .ncm file.
 *
 * @param inputPath  path to the .ncm file
 * @param outputDir  directory where the decrypted file and cover art will be saved
 * @return JSON string with the result
 */
JNIEXPORT jstring JNICALL
Java_com_ncmdump_NcmDecryptor_decryptFile(JNIEnv *env, jclass clazz,
                                           jstring inputPath, jstring outputDir)
{
    const char *inputPathCStr = env->GetStringUTFChars(inputPath, nullptr);
    const char *outputDirCStr = env->GetStringUTFChars(outputDir, nullptr);

    std::ostringstream result;

    try
    {
        NeteaseCrypt crypt(inputPathCStr);
        crypt.Dump(outputDirCStr);

        result << "{"
               << "\"success\":true,"
               << "\"outputPath\":\"" << jniEscapeJson(crypt.dumpFilepath().u8string()) << "\","
               << "\"metadata\":" << crypt.getMetadataJson();

        // Save cover art if available
        if (crypt.hasCoverArt())
        {
            std::string ext = crypt.getCoverArtExtension();
            fs::path coverPath = crypt.dumpFilepath();
            coverPath.replace_extension("." + ext);

            std::ofstream coverFile(coverPath, std::ofstream::out | std::ofstream::binary);
            const std::string &coverData = crypt.getCoverArtData();
            coverFile.write(coverData.c_str(), coverData.size());
            coverFile.close();

            result << ",\"coverArtPath\":\"" << jniEscapeJson(coverPath.u8string()) << "\"";
        }

        result << "}";
    }
    catch (const std::invalid_argument &e)
    {
        result << "{"
               << "\"success\":false,"
               << "\"error\":\"" << jniEscapeJson(e.what()) << "\""
               << "}";
    }
    catch (const std::exception &e)
    {
        result << "{"
               << "\"success\":false,"
               << "\"error\":\"" << jniEscapeJson(e.what()) << "\""
               << "}";
    }
    catch (...)
    {
        result << "{"
               << "\"success\":false,"
               << "\"error\":\"Unknown error\""
               << "}";
    }

    env->ReleaseStringUTFChars(inputPath, inputPathCStr);
    env->ReleaseStringUTFChars(outputDir, outputDirCStr);

    return env->NewStringUTF(result.str().c_str());
}

} // extern "C"
