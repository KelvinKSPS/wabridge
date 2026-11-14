package com.orangepi.wabridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class ReplyArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preservesExactBytesAndExportsMetadata() {
        val directory = temporary.newFolder("archive")
        val source = File(temporary.root, "reply_example_123.ogg")
        val bytes = ByteArray(20000) { (it % 251).toByte() }
        source.writeBytes(bytes)
        val folder = ReplyArchive.preserve(directory, source, JSONObject().put("to", "5511999990001"))
        assertArrayEquals(bytes, File(folder, source.name).readBytes())
        val metadata = JSONObject(File(folder, "metadata.json").readText())
        assertEquals(20000L, metadata.getLong("bytes"))
        assertEquals(64, metadata.getString("sha256").length)
        assertEquals(ReplyArchive.sha256(source), metadata.getString("sha256"))
        val output = ByteArrayOutputStream()
        ReplyArchive.export(folder, output)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf(source.name, "metadata.json"), entries.keys)
        assertArrayEquals(bytes, entries[source.name])
        assertTrue(source.exists())
    }

    @Test fun retainsOnlyThreeNewestRecordings() {
        val directory = temporary.newFolder("bounded")
        for (time in listOf(3, 1, 2, 4)) {
            val source = File(temporary.root, "reply_example_$time.ogg")
            source.writeBytes(byteArrayOf(time.toByte()))
            ReplyArchive.preserve(directory, source, JSONObject())
        }
        assertEquals(listOf("reply_example_4", "reply_example_3", "reply_example_2"), ReplyArchive.entries(directory).map { it.name })
    }

    @Test fun hashesKnownContent() {
        val source = File(temporary.root, "abc.ogg")
        source.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReplyArchive.sha256(source))
    }
}
