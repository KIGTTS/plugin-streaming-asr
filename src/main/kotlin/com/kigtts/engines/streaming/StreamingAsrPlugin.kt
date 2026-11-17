package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import org.json.JSONObject

class StreamingAsrPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("engine.streaming-asr", "流式语音识别",
        "内置中文模型，装好插件即可离线流式识别", version = "0.8.7")
    override val localization = PluginLocalization("zh-CN", listOf("zh-CN", "en", "ko"))
    private var strings = PluginStrings(emptyMap())
    private lateinit var environment: PluginEnvironment
    private lateinit var store: RecognitionPackStore
    private lateinit var pages: RecognitionPackPages
    private lateinit var engine: IncrementalAsrEngine
    override val asr: AsrEngine get() = engine
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        strings = PluginStrings.fromAssets(environment.assets)
        store = RecognitionPackStore(environment.storage, BundledRecognitionModel.readFrom(environment.assets))
        pages = RecognitionPackPages(store); engine = IncrementalAsrEngine(store)
    }
    override val contributions get() = contributions(PluginContext("", ""))
    override fun contributions(context: PluginContext) = PluginContributions(drawerEntries = listOf(
        DrawerEntry("packs", strings.text("packs.title", context.locale, fallback = "流式识别包"), "packs", PluginIcon.MIC, settingsPage = true)))
    override fun page(pageId: String, context: PluginContext) = pages.page(pageId) { key, fallback ->
        strings.text(key, context.locale, fallback = fallback)
    }
    override suspend fun close() { if (::engine.isInitialized) engine.close() }
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome = withContext(Dispatchers.IO) {
        fun copy(key: String, fallback: String) = strings.text(key, context.locale, fallback = fallback)
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
            "import" -> PluginOutcome.ImportResource(copy("action.import", "导入识别包（ZIP / tar.bz2 / tar.gz / .kigasr）"), "resources/inbox", "imported")
            "imported" -> import(null)
            "update" -> PluginOutcome.ImportResource(copy("action.update", "更新识别包，保留名称、备注和顺序"), "resources/inbox", "updated/$id")
            "updated" -> import(id)
            "name", "remark", "languages" -> PluginOutcome.TextInput(when (operation) {
                "name" -> copy("action.name", "识别包名称"); "remark" -> copy("action.remark", "识别包备注"); else -> copy("action.languages", "语言代码，用逗号分隔（仅修改说明）")
            }, "$operation-save/$id", when (operation) { "name" -> pack().name; "remark" -> pack().remark; else -> pack().languages.joinToString(",") }, multiline = operation == "remark")
            "name-save" -> { store.edit(id) { it.copy(name = value()) }; PluginOutcome.RefreshUi }
            "remark-save" -> { store.edit(id) { it.copy(remark = value()) }; PluginOutcome.RefreshUi }
            "languages-save" -> { store.edit(id) { it.copy(languages = value().split(',').map(String::trim)) }; PluginOutcome.RefreshUi }
            "delete" -> PluginOutcome.Menu(strings.text("action.delete_title", context.locale, mapOf("name" to pack().name), "删除“{name}”及模型文件？"), listOf(PageAction("delete-confirm/$id", copy("action.delete", "确认删除"), PluginIcon.CLOSE)))
            "delete-confirm" -> { store.delete(id); PluginOutcome.OpenPage("packs") }
            "export" -> {
                val result = store.export(id)
                val filename = pack().name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100) + ".kigasr"
                PluginOutcome.ExportResource(copy("action.export", "导出识别包"), PluginResource(result.relativeTo(environment.storage.root).invariantSeparatorsPath), filename, "application/zip", temporary = true)
            }
            "threads" -> PluginOutcome.TextInput(copy("action.threads", "推理线程（1–4）"), "threads-save", store.threads.toString())
            "threads-save" -> { store.setThreads(value().toInt()); PluginOutcome.RefreshUi }
            "download" -> PluginOutcome.OpenPage("catalog")
            "catalog-download" -> {
                if (id == "zh14m" && store.hasBundledModel) {
                    store.choose(BundledRecognitionModel.ID)
                    return@withContext PluginOutcome.OpenPage("packs")
                }
                val model = RecognitionModelCatalog.models.first { it.id == id }
                val source = environment.download(model.url, "downloads/${model.archive}.tar.bz2", model.sha256)
                store.import(source); PluginOutcome.OpenPage("packs")
            }
            "download-custom" -> PluginOutcome.TextInput(copy("action.url", "识别包下载地址（HTTPS）"), "download-url")
            "download-url" -> {
                val uri = URI(value()); require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { copy("action.https", "请输入 HTTPS 地址") }
                atomicText(environment.storage.file("config/download.json"), JSONObject().put("url", uri.toString()).toString())
                PluginOutcome.TextInput(copy("action.sha_prompt", "SHA-256（来自可信模型发布者，64 位）"), "download-start")
            }
            "download-start" -> {
                val hash = value().lowercase(); require(hash.matches(Regex("[a-f0-9]{64}"))) { copy("action.sha_invalid", "需要 64 位 SHA-256") }
                val url = JSONObject(environment.storage.file("config/download.json").readText()).getString("url")
                val source = environment.download(url, "downloads/model-$hash.archive", hash)
                store.import(source); PluginOutcome.RefreshUi
            }
            else -> PluginOutcome.Message(copy("action.unknown", "未知识别包操作"))
        }
    }
}
