package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamingAsrPluginTest {
    private lateinit var root: File
    @Before fun setup() { root = Files.createTempDirectory("streaming-plugin-test").toFile() }
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun declaredLibraryRoutesImportSelectionFilterReorderAndDeleteConfirmation() = runBlocking<Unit> {
        val storage = PluginStorage(root)
        val plugin = StreamingAsrPlugin()
        plugin.attach(object : PluginEnvironment {
            override val storage = storage; override val assets = File(root, "assets")
            override suspend fun download(url: String, relativePath: String, sha256: String?) = error("No download requested")
        })
        val import = plugin.execute("import", PluginContext("", "")) as PluginOutcome.ImportResource
        assertFalse(import.zip); val inbox = storage.directory(import.directory)
        val first = fixture(inbox); plugin.execute(import.actionId, PluginContext("", "", inputs = mapOf("resourcePath" to first.relativeTo(root).invariantSeparatorsPath)))
        assertFalse(first.exists()); val second = fixture(inbox, "en", "English")
        plugin.execute(import.actionId, PluginContext("", "", inputs = mapOf("resourcePath" to second.relativeTo(root).invariantSeparatorsPath)))
        val page = requireNotNull(plugin.page("packs", PluginContext("", ""))).management!!
        assertEquals(2, page.items.size); assertEquals("reorder", page.reorderAction)
        plugin.execute("reorder", PluginContext("", "", inputs = mapOf("order" to PluginJson.encodeToString(page.items.map { it.id }.reversed()))))
        val sorted = plugin.page("packs", PluginContext("", ""))!!.management!!
        assertEquals(page.items.last().id, sorted.items.first().id)
        plugin.execute("use/${sorted.items.first().id}", PluginContext("", ""))
        assertEquals(StreamingKind.INCREMENTAL, plugin.asr.info.streaming); assertEquals(EngineAvailability.READY, plugin.asr.info.availability)
        plugin.execute("filter/en", PluginContext("", "")); assertEquals(1, plugin.page("packs", PluginContext("", ""))!!.management!!.items.size)
        val confirmation = plugin.execute("delete/${sorted.items.first().id}", PluginContext("", "")) as PluginOutcome.Menu
        assertEquals(2, RecognitionPackStore(storage).list().size)
        plugin.execute(confirmation.items.single().id, PluginContext("", "")); assertEquals(1, RecognitionPackStore(storage).list().size)
    }
    @Test fun catalogDownloadsAreScopedAndCheckedAndIncludeBilingualModel() = runBlocking<Unit> {
        val storage = PluginStorage(root); val model = RecognitionModelCatalog.models.first { it.languages == listOf("zh", "en") }
        val plugin = StreamingAsrPlugin(); var requested = false
        plugin.attach(object : PluginEnvironment {
            override val storage = storage; override val assets = File(root, "assets")
            override suspend fun download(url: String, relativePath: String, sha256: String?): File {
                assertEquals(model.url, url); assertEquals(model.sha256, sha256); assertTrue(relativePath.startsWith("downloads/"))
                requested = true; return fixture(storage.downloads)
            }
        })
        assertEquals(PluginOutcome.OpenPage("catalog"), plugin.execute("download", PluginContext("", "")))
        assertEquals(3, plugin.page("catalog", PluginContext("", ""))!!.management!!.items.size)
        assertEquals(PluginOutcome.OpenPage("packs"), plugin.execute("catalog-download/${model.id}", PluginContext("", "")))
        assertTrue(requested); assertEquals(1, RecognitionPackStore(storage).list().size)
    }
}
