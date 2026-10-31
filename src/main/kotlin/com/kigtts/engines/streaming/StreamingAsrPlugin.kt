package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import org.json.JSONObject

class StreamingAsrPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("engine.streaming-asr", "流式语音识别",
        "边说边显示字幕，支持多种语言的识别包", version = "0.8.5")
    private lateinit var environment: PluginEnvironment
    private lateinit var store: RecognitionPackStore
    private lateinit var pages: RecognitionPackPages
    private lateinit var engine: IncrementalAsrEngine
    override val asr: AsrEngine get() = engine
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment; store = RecognitionPackStore(environment.storage)
        pages = RecognitionPackPages(store); engine = IncrementalAsrEngine(store)
    }
    override val contributions = PluginContributions(drawerEntries = listOf(
        DrawerEntry("packs", "流式识别包", "packs", PluginIcon.MIC, settingsPage = true)))
    override fun page(pageId: String, context: PluginContext) = pages.page(pageId)
    override suspend fun close() { if (::engine.isInitialized) engine.close() }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = withContext(Dispatchers.IO) {
        val operation = actionId.substringBefore('/'); val id = actionId.substringAfter('/', "")
        fun pack() = store.list().first { it.id == id }
        fun value() = context.inputs["value"].orEmpty().trim()
        fun import(replacing: String?): PluginOutcome {
            val path = requireNotNull(context.inputs["resourcePath"])
            require(path.startsWith("resources/inbox/"))
            val source = environment.storage.file(path)
            try { store.import(source, replacing) } finally { source.delete() }
            return PluginOutcome.RefreshUi
        }
        when (operation) {
            "packs", "settings", "pack" -> PluginOutcome.OpenPage(actionId)
            "filter" -> { store.filter = id; PluginOutcome.RefreshUi }
            "reorder" -> { store.reorder(PluginJson.decodeFromString(requireNotNull(context.inputs["order"]))); PluginOutcome.RefreshUi }
            "use" -> { store.choose(id); PluginOutcome.RefreshUi }
            "pin" -> { store.edit(id) { it.copy(pinned = !it.pinned) }; PluginOutcome.RefreshUi }
            "import" -> PluginOutcome.ImportResource("导入识别包（ZIP / tar.bz2 / tar.gz / .kigasr）", "resources/inbox", "imported")
            "imported" -> import(null)
            "update" -> PluginOutcome.ImportResource("更新识别包，保留名称、备注和顺序", "resources/inbox", "updated/$id")
            "updated" -> import(id)
            "name", "remark", "languages" -> PluginOutcome.TextInput(when (operation) {
                "name" -> "识别包名称"; "remark" -> "识别包备注"; else -> "语言代码，用逗号分隔（仅修改说明）"
            }, "$operation-save/$id", when (operation) { "name" -> pack().name; "remark" -> pack().remark; else -> pack().languages.joinToString(",") }, multiline = operation == "remark")
            "name-save" -> { store.edit(id) { it.copy(name = value()) }; PluginOutcome.RefreshUi }
            "remark-save" -> { store.edit(id) { it.copy(remark = value()) }; PluginOutcome.RefreshUi }
            "languages-save" -> { store.edit(id) { it.copy(languages = value().split(',').map(String::trim)) }; PluginOutcome.RefreshUi }
            "delete" -> PluginOutcome.Menu("确认删除“${pack().name}”及模型文件？", listOf(PageAction("delete-confirm/$id", "确认删除", PluginIcon.CLOSE)))
            "delete-confirm" -> { store.delete(id); PluginOutcome.OpenPage("packs") }
            "export" -> {
                val result = store.export(id)
                val filename = pack().name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".kigasr"
                PluginOutcome.ExportResource("导出识别包", PluginResource(result.relativeTo(environment.storage.root).invariantSeparatorsPath), filename, "application/zip", temporary = true)
            }
            "threads" -> PluginOutcome.TextInput("推理线程（1–4）", "threads-save", store.threads.toString())
            "threads-save" -> { store.setThreads(value().toInt()); PluginOutcome.RefreshUi }
            "download" -> PluginOutcome.OpenPage("catalog")
            "catalog-download" -> {
                val model = RecognitionModelCatalog.models.first { it.id == id }
                val source = environment.download(model.url, "downloads/${model.archive}.tar.bz2", model.sha256)
                store.import(source); PluginOutcome.OpenPage("packs")
            }
            "download-custom" -> PluginOutcome.TextInput("识别包下载地址（HTTPS）", "download-url")
            "download-url" -> {
                val uri = URI(value()); require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "请输入 HTTPS 地址" }
                atomicText(environment.storage.file("config/download.json"), JSONObject().put("url", uri.toString()).toString())
                PluginOutcome.TextInput("SHA-256（来自可信模型发布者，64 位）", "download-start")
            }
            "download-start" -> {
                val hash = value().lowercase(); require(hash.matches(Regex("[a-f0-9]{64}"))) { "需要 64 位 SHA-256" }
                val url = JSONObject(environment.storage.file("config/download.json").readText()).getString("url")
                val source = environment.download(url, "downloads/model-$hash.archive", hash)
                store.import(source); PluginOutcome.RefreshUi
            }
            else -> PluginOutcome.Message("未知识别包操作")
        }
    }
}
