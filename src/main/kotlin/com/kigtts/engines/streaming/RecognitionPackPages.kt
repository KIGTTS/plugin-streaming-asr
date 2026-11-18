package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*

internal class RecognitionPackPages(private val store: RecognitionPackStore) {
    fun page(id: String, copy: (String, String) -> String = { _, fallback -> fallback }): PluginPage? = when {
        id == "catalog" -> RecognitionModelCatalog.page(store.hasBundledModel, copy)
        id == "packs" -> {
            val all = store.list(); val selected = store.selected()
            val visible = all.filter { store.filter == "all" || store.filter in it.languages }
            PluginPage(id, copy("packs.title", "流式识别包"), listOf(
                PageBlock.Notice(if (selected == null) copy("packs.empty", "请添加识别包") else copy("packs.current_label", "当前") + ": " + selected.displayName(copy)),
                PageBlock.Group(copy("packs.group", "识别包"), listOf(
                    PageBlock.Input(copy("packs.import", "导入识别包"), copy("packs.from_file", "从文件添加"), "import", icon = PluginIcon.ADD, symbol = "file_open"),
                    PageBlock.Input(copy("packs.get", "获取识别包"), copy("packs.choose_language", "选择需要的语言"), "download", icon = PluginIcon.ADD))),
                PageBlock.Group(copy("packs.settings", "识别设置"), listOf(
                    PageBlock.Input(copy("packs.inference", "推理设置"), copy("packs.options", "线程与识别选项"), "settings", icon = PluginIcon.SETTINGS)))
            ), management = ManagementList("recognition-packs", visible.map { pack ->
                val bundled = store.isBundled(pack.id)
                val use = PageAction("use/${pack.id}", copy("packs.use", "选用"), PluginIcon.MIC)
                val secondary = if (bundled) emptyList() else listOf(
                    PageAction("pin/${pack.id}", if (pack.pinned) copy("packs.unpin", "取消置顶") else copy("packs.pin", "置顶"), PluginIcon.PIN),
                    PageAction("export/${pack.id}", copy("packs.export", "导出"), PluginIcon.EXPORT), PageAction("delete/${pack.id}", copy("packs.delete", "删除"), PluginIcon.CLOSE))
                val info = if (bundled) copy("packs.bundled", "内置模型 · 离线可用") else pack.languages.joinToString(" / ") + " · " + pack.architecture.label(copy)
                ManagementItem(pack.id, pack.displayName(copy), info +
                    if (pack.remark.isBlank()) "" else "\n${pack.remark}", PluginIcon.MIC,
                    selected = pack.id == selected?.id, pinned = pack.pinned,
                    group = if (bundled) "bundled" else if (pack.pinned) "pinned" else "normal",
                    primaryAction = "pack/${pack.id}", actions = listOf(use) + secondary)
            }, "reorder", copy("packs.no_language", "暂无此语言的识别包"), (listOf("all") + all.flatMap { it.languages }.distinct()).map { language ->
                ManagementFilter(language, if (language == "all") copy("packs.all", "全部") else language, "filter/$language", store.filter == language)
            }))
        }
        id.startsWith("pack/") -> store.list().firstOrNull { it.id == id.substringAfter('/') }?.let { pack ->
            val key = pack.id
            val displayName = pack.displayName(copy)
            PluginPage(id, displayName, buildList {
                add(PageBlock.Text(displayName, heading = true))
                add(
                PageBlock.Notice(if (store.selected()?.id == key) copy("packs.active", "正在使用") else copy("packs.inactive", "未选用")),
                )
                add(PageBlock.Text(copy("packs.language_label", "语言") + ": ${pack.languages.joinToString(" / ")}\n${pack.architecture.label(copy)} · ${pack.sampleRate} Hz\n${pack.remark}"))
                val choose = PageBlock.Input(copy("packs.select", "选用识别包"), "", "use/$key", icon = PluginIcon.MIC)
                if (store.isBundled(key)) {
                    add(PageBlock.Notice(copy("packs.bundled_notice", "内置模型可离线使用，随插件更新")))
                    add(PageBlock.Group(copy("packs.config", "识别包设置"), listOf(choose)))
                } else {
                add(PageBlock.Group(copy("packs.config", "识别包设置"), listOf(choose,
                    PageBlock.Input(copy("packs.name", "名称"), pack.name, "name/$key", icon = PluginIcon.EDIT),
                    PageBlock.Input(copy("packs.remark", "备注"), pack.remark, "remark/$key", icon = PluginIcon.EDIT),
                    PageBlock.Input(copy("packs.language", "语言说明"), pack.languages.joinToString(" / "), "languages/$key", icon = PluginIcon.MIC))))
                add(PageBlock.Group(copy("packs.manage", "管理"), listOf(
                    PageBlock.Input(copy("packs.update", "更新识别包"), "", "update/$key", icon = PluginIcon.ADD),
                    PageBlock.Input(copy("packs.export", "导出"), "", "export/$key", icon = PluginIcon.EXPORT),
                    PageBlock.Input(copy("packs.delete", "删除"), "", "delete/$key", icon = PluginIcon.CLOSE))))
                }
            })
        }
        id == "settings" -> PluginPage(id, copy("packs.streaming_settings", "流式识别设置"), listOf(
            PageBlock.Group(copy("packs.infer", "推理"), listOf(
                PageBlock.Input(copy("packs.threads", "推理线程"), store.threads.toString(), "threads", icon = PluginIcon.SETTINGS))),
            PageBlock.Group(copy("packs.resources", "识别资源"), listOf(
                PageBlock.Input(copy("packs.management", "识别包管理"), "", "packs", icon = PluginIcon.MIC)))))
        else -> null
    }
}

internal fun OnlineArchitecture.label(copy: (String, String) -> String) = when (this) {
    OnlineArchitecture.TRANSDUCER -> copy("architecture.transducer", "在线 Transducer")
    OnlineArchitecture.PARAFORMER -> copy("architecture.paraformer", "在线 Paraformer")
    OnlineArchitecture.ZIPFORMER_CTC -> copy("architecture.zipformer", "在线 Zipformer CTC")
    OnlineArchitecture.NEMO_CTC -> copy("architecture.nemo", "在线 NeMo CTC")
}

private fun RecognitionPack.displayName(copy: (String, String) -> String): String =
    if (id == BundledRecognitionModel.ID) copy("packs.bundled_name", "中文（轻量，内置）") else name
