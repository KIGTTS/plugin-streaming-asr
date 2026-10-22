package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.nio.file.AtomicMoveNotSupportedException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class RecognitionPackStore(val storage: PluginStorage) {
    val session = Mutex()
    private val settingsFile = storage.file("config/recognition-packs.json").also { storage.configuration }
    private var settings = runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())
    val threads get() = settings.optInt("threads", 2).coerceIn(1, 4)
    var filter: String
        get() = settings.optString("filter", "all")
        set(value) { settings.put("filter", value); save() }
    fun list(): List<RecognitionPack> = storage.models.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9-]{36}")) }
        .mapNotNull { runCatching { RecognitionPack.read(it) }.getOrNull() }
        .sortedWith(compareByDescending<RecognitionPack> { it.pinned }.thenBy { it.order }.thenBy { it.id })
    fun selected(): RecognitionPack? = list().let { packs -> packs.firstOrNull { it.id == settings.optString("selected") } ?: packs.firstOrNull() }
    fun choose(id: String) = change { require(list().any { it.id == id }); settings.put("selected", id); save() }
    fun setThreads(value: Int) = change { require(value in 1..4); settings.put("threads", value); save() }
    fun edit(id: String, transform: (RecognitionPack) -> RecognitionPack) = change {
        val pack = list().first { it.id == id }; val changed = transform(pack)
        val original = File(pack.directory, "asr-pack.json").readText()
        try { atomicText(File(pack.directory, "asr-pack.json"), changed.json().toString()); RecognitionPack.read(pack.directory) }
        catch (error: Exception) { atomicText(File(pack.directory, "asr-pack.json"), original); throw error }
    }
    fun reorder(proposed: List<String>) = change {
        val all = list(); val visible = all.filter { filter == "all" || filter in it.languages }
        ItemOrder.validate(proposed, visible.map { it.id })
        val pins = proposed.map { id -> visible.first { it.id == id }.pinned }
        require(pins.zipWithNext().none { !it.first && it.second })
        val combined = all.map { it.id }.toMutableList(); val slots = combined.indices.filter { combined[it] in proposed }
        slots.forEachIndexed { index, slot -> combined[slot] = proposed[index] }
        combined.forEachIndexed { index, id -> val pack = all.first { it.id == id }; atomicText(File(pack.directory, "asr-pack.json"), pack.copy(order = index.toLong()).json().toString()) }
    }
    fun import(source: File, replacing: String? = null): RecognitionPack {
        val stage = File(storage.temporary, "asr-import-${UUID.randomUUID()}").apply { mkdirs() }
        val backup = File(storage.temporary, "asr-previous-${UUID.randomUUID()}")
        try {
            PackArchive.extract(source, stage)
            val detected = OfficialPackDiscovery.describe(stage)
            val normalized = detected.copy(directory = stage, files = detected.files.mapValues { detected.file(it.key).relativeTo(stage).invariantSeparatorsPath })
            if (detected.directory != stage) File(detected.directory, "asr-pack.json").delete()
            atomicText(File(stage, "asr-pack.json"), normalized.json().toString()); RecognitionPack.read(stage)
            return change {
                val old = replacing?.let { id -> list().first { it.id == id } }
                val id = old?.id ?: UUID.randomUUID().toString()
                val candidate = normalized.copy(name = old?.name ?: normalized.name, remark = old?.remark ?: normalized.remark,
                    pinned = old?.pinned ?: normalized.pinned, order = old?.order ?: (list().maxOfOrNull { it.order }?.plus(1) ?: 0))
                atomicText(File(stage, "asr-pack.json"), candidate.json().toString()); RecognitionPack.read(stage)
                val target = File(storage.models, id)
                if (old != null) require(target.renameTo(backup)) { "无法备份旧识别包" }
                if (!stage.renameTo(target)) { if (old != null) require(backup.renameTo(target)) { "旧包恢复失败，备份保留在 tmp" }; error("识别包安装失败") }
                if (settings.optString("selected").isBlank()) { settings.put("selected", id); save() }
                backup.deleteRecursively(); RecognitionPack.read(target)
            }
        } finally { stage.deleteRecursively() }
    }
    fun delete(id: String) = change {
        val target = list().first { it.id == id }.directory
        val retired = File(storage.temporary, "asr-delete-${UUID.randomUUID()}")
        require(target.renameTo(retired))
        if (settings.optString("selected") == id) { settings.remove("selected"); save() }
        retired.deleteRecursively()
    }
    fun export(id: String): File = change {
        val pack = list().first { it.id == id }
        val output = File(storage.temporary, "asr-export-${UUID.randomUUID()}.kigasr")
        try {
            ZipOutputStream(output.outputStream()).use { zip ->
                pack.directory.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(pack.directory).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }; output
        } catch (error: Exception) { output.delete(); throw error }
    }
    private fun save() = atomicText(settingsFile, settings.toString())
    private inline fun <T> change(block: () -> T): T {
        check(session.tryLock()) { "请先停止当前识别，再修改识别包或设置" }
        return try { block() } finally { session.unlock() }
    }
}

internal fun atomicText(file: File, text: String) {
    val temporary = File(file.parentFile, ".${file.name}.tmp"); temporary.writeText(text, Charsets.UTF_8)
    try { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE) }
    catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING) }
    finally { temporary.delete() }
}
