package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*

internal class RecognitionPackPages(private val store: RecognitionPackStore) {
    fun page(id: String): PluginPage? = when {
        id == "catalog" -> RecognitionModelCatalog.page()
        id == "packs" -> {
            val all = store.list(); val selected = store.selected()?.id
            val visible = all.filter { store.filter == "all" || store.filter in it.languages }
            PluginPage(id, "流式识别包", listOf(
                PageBlock.Notice(if (selected == null) "请添加识别包" else "当前：${store.selected()!!.name}"),
                PageBlock.Group("识别包", listOf(
                    PageBlock.Input("导入识别包", "从文件添加", "import", icon = PluginIcon.ADD, symbol = "file_open"),
                    PageBlock.Input("获取识别包", "选择需要的语言", "download", icon = PluginIcon.ADD))),
                PageBlock.Group("识别设置", listOf(
                    PageBlock.Input("推理设置", "线程与识别选项", "settings", icon = PluginIcon.SETTINGS)))
            ), management = ManagementList("recognition-packs", visible.map { pack ->
                ManagementItem(pack.id, pack.name, pack.languages.joinToString(" / ") + " · " + pack.architecture.label() +
                    if (pack.remark.isBlank()) "" else "\n${pack.remark}", PluginIcon.MIC,
                    selected = pack.id == selected, pinned = pack.pinned, group = if (pack.pinned) "pinned" else "normal",
                    primaryAction = "pack/${pack.id}", actions = listOf(PageAction("use/${pack.id}", "选用", PluginIcon.MIC),
                        PageAction("pin/${pack.id}", if (pack.pinned) "取消置顶" else "置顶", PluginIcon.PIN),
                        PageAction("export/${pack.id}", "导出", PluginIcon.EXPORT), PageAction("delete/${pack.id}", "删除", PluginIcon.CLOSE)))
            }, "reorder", "暂无此语言的识别包", (listOf("all") + all.flatMap { it.languages }.distinct()).map { language ->
                ManagementFilter(language, if (language == "all") "全部" else language, "filter/$language", store.filter == language)
            }))
        }
        id.startsWith("pack/") -> store.list().firstOrNull { it.id == id.substringAfter('/') }?.let { pack ->
            val key = pack.id
            PluginPage(id, pack.name, listOf(PageBlock.Text(pack.name, heading = true),
                PageBlock.Notice(if (store.selected()?.id == key) "正在使用" else "未选用"),
                PageBlock.Text("语言：${pack.languages.joinToString(" / ")}\n${pack.architecture.label()} · ${pack.sampleRate} Hz\n${pack.remark}"),
                PageBlock.Group("识别包设置", listOf(
                    PageBlock.Input("选用识别包", "", "use/$key", icon = PluginIcon.MIC),
                    PageBlock.Input("名称", pack.name, "name/$key", icon = PluginIcon.EDIT),
                    PageBlock.Input("备注", pack.remark, "remark/$key", icon = PluginIcon.EDIT),
                    PageBlock.Input("语言说明", pack.languages.joinToString(" / "), "languages/$key", icon = PluginIcon.MIC))),
                PageBlock.Group("管理", listOf(
                    PageBlock.Input("更新识别包", "", "update/$key", icon = PluginIcon.ADD),
                    PageBlock.Input("导出", "", "export/$key", icon = PluginIcon.EXPORT),
                    PageBlock.Input("删除", "", "delete/$key", icon = PluginIcon.CLOSE)))
            ))
        }
        id == "settings" -> PluginPage(id, "流式识别设置", listOf(
            PageBlock.Group("推理", listOf(
                PageBlock.Input("推理线程", store.threads.toString(), "threads", icon = PluginIcon.SETTINGS))),
            PageBlock.Group("识别资源", listOf(
                PageBlock.Input("识别包管理", "", "packs", icon = PluginIcon.MIC)))))
        else -> null
    }
}

internal fun OnlineArchitecture.label() = when (this) {
    OnlineArchitecture.TRANSDUCER -> "在线 Transducer"
    OnlineArchitecture.PARAFORMER -> "在线 Paraformer"
    OnlineArchitecture.ZIPFORMER_CTC -> "在线 Zipformer CTC"
    OnlineArchitecture.NEMO_CTC -> "在线 NeMo CTC"
}
