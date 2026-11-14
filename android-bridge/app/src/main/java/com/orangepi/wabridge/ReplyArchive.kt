package com.orangepi.wabridge

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReplyArchive {
    fun preserve(directory: File, recording: File, metadata: JSONObject): File {
        directory.mkdirs()
        val folder = File(directory, recording.nameWithoutExtension)
        folder.mkdirs()
        val original = File(folder, recording.name)
        recording.copyTo(original, overwrite = true)
        metadata.put("file", original.name)
            .put("bytes", original.length())
            .put("sha256", sha256(original))
        File(folder, "metadata.json").writeText(metadata.toString(2))
        entries(directory).drop(3).forEach { it.deleteRecursively() }
        return folder
    }

    fun entries(directory: File): List<File> = directory.listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "metadata.json").isFile }
        .sortedByDescending { it.name.substringAfterLast('_').toLongOrNull() ?: 0L }

    fun export(folder: File, output: OutputStream) {
        val metadataFile = File(folder, "metadata.json")
        val metadata = JSONObject(metadataFile.readText())
        ZipOutputStream(output).use { zip ->
            listOf(File(folder, metadata.getString("file")), metadataFile).forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
