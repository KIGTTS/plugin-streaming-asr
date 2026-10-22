package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecognitionPackStoreTest {
    private lateinit var root: File
    private lateinit var store: RecognitionPackStore
    @Before fun setup() { root = Files.createTempDirectory("recognition-pack-test").toFile(); store = RecognitionPackStore(PluginStorage(root)) }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun languagePacksCoexistAndSelectionAndFilteredOrderSurviveRestart() {
        val zh = store.import(fixture(root)); val en = store.import(fixture(root, "en", "English")); val zh2 = store.import(fixture(root, "zh", "第二个中文"))
        store.choose(en.id); store.filter = "zh"; store.reorder(listOf(zh2.id, zh.id))
        val reopened = RecognitionPackStore(PluginStorage(root))
        assertEquals(en.id, reopened.selected()!!.id)
        assertEquals(listOf(zh2.id, en.id, zh.id), reopened.list().map { it.id })
        assertEquals("zh", reopened.filter)
        reopened.edit(zh.id) { it.copy(pinned = true) }
        assertEquals(zh.id, reopened.list().first().id)
        assertThrows(IllegalArgumentException::class.java) { reopened.reorder(listOf(zh2.id, zh.id)) }
    }
    @Test fun updateKeepsSelectionAndUserMetadataAndInvalidArchivePreservesWorkingModel() {
        val original = store.import(fixture(root)); store.choose(original.id)
        store.edit(original.id) { it.copy(name = "自定义", remark = "备注", pinned = true) }
        store.import(fixture(root, "en", "Updated", "new-model"), original.id)
        val updated = store.selected()!!
        assertEquals(original.id, updated.id); assertEquals("自定义", updated.name); assertEquals("备注", updated.remark)
        assertTrue(updated.pinned); assertEquals(listOf("en"), updated.languages); assertEquals("new-model", updated.file("encoder").readText())
        val bad = File(root, "bad.zip"); zip(bad, mapOf("tokens.txt" to "missing models"))
        assertThrows(IllegalArgumentException::class.java) { store.import(bad, original.id) }
        assertEquals("new-model", store.selected()!!.file("encoder").readText()); assertTrue(store.storage.temporary.listFiles().orEmpty().isEmpty())
    }
    @Test fun exportedNestedManifestRoundTripsWithLanguagesAndOriginalLicense() {
        val input = fixture(root, "ko", "한국어"); val nested = File(root, "nested.zip")
        java.util.zip.ZipFile(input).use { original -> zip(nested, original.entries().asSequence().associate { "package/" + it.name to original.getInputStream(it).reader().readText() }) }
        val imported = store.import(nested); store.edit(imported.id) { it.copy(remark = "说明") }
        val exported = store.export(imported.id); val copy = store.import(exported)
        assertNotEquals(imported.id, copy.id); assertEquals(listOf("ko"), copy.languages); assertEquals("说明", copy.remark)
        assertEquals("fixture license", File(copy.directory, "package/LICENSE").readText())
    }
    @Test fun deletingCurrentPackChoosesRemainingPackAndCannotMutateDuringRecognition() {
        val first = store.import(fixture(root)); val second = store.import(fixture(root, "en"))
        store.choose(first.id); assertTrue(store.session.tryLock())
        try { assertThrows(IllegalStateException::class.java) { store.delete(first.id) }; assertThrows(IllegalStateException::class.java) { store.choose(second.id) } }
        finally { store.session.unlock() }
        store.delete(first.id); assertEquals(second.id, store.selected()!!.id); assertFalse(first.directory.exists())
    }
    @Test fun traversalAndTarLinksAreRejectedAndStageIsCleaned() {
        val bad = File(root, "bad.zip"); zip(bad, mapOf("../outside" to "bad"))
        assertThrows(IllegalArgumentException::class.java) { store.import(bad) }
        val linked = File(root, "link.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(linked.outputStream())).use { tar ->
            val entry = TarArchiveEntry("model", TarConstants.LF_SYMLINK); entry.linkName = "../../outside"
            tar.putArchiveEntry(entry); tar.closeArchiveEntry()
        }
        assertThrows(IllegalArgumentException::class.java) { store.import(linked) }
        assertTrue(store.storage.temporary.listFiles().orEmpty().isEmpty()); assertNull(store.selected())
    }
    @Test fun officialStreamingTarLayoutSelectsQuantizedFilesAndInfersLanguage() {
        val archive = File(root, "official.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
            listOf("encoder.onnx", "encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt").forEach { name ->
                val content = "model".toByteArray(); val entry = TarArchiveEntry("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/$name"); entry.size = content.size.toLong()
                tar.putArchiveEntry(entry); tar.write(content); tar.closeArchiveEntry()
            }
        }
        val installed = store.import(archive)
        assertEquals(listOf("zh"), installed.languages); assertEquals("encoder.int8.onnx", installed.file("encoder").name)
        assertEquals(OnlineArchitecture.TRANSDUCER, installed.architecture)
    }
    @Test fun offlineModelsAndInvalidLanguageMetadataNeverBecomeOnlinePacks() {
        val archive = File(root, "offline.zip"); zip(archive, mapOf("tokens.txt" to "tokens", "model.onnx" to "SenseVoice"))
        assertThrows(IllegalArgumentException::class.java) { store.import(archive) }
        val pack = store.import(fixture(root))
        assertThrows(IllegalArgumentException::class.java) { store.edit(pack.id) { it.copy(languages = listOf("not a language")) } }
        assertEquals(listOf("zh"), store.selected()!!.languages)
    }
}
