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
                PageBlock.Text("边说边显示字幕。需要识别其它语言时，请选择支持该语言的识别包。"),
                PageBlock.Actions(listOf(PageAction("import", "导入识别包", PluginIcon.ADD),
                    PageAction("download", "下载识别包", PluginIcon.ADD), PageAction("settings", "识别设置", PluginIcon.SETTINGS)))
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
                PageBlock.Actions(listOf(PageAction("use/$key", "选用识别包", PluginIcon.MIC),
                    PageAction("name/$key", "修改名称", PluginIcon.EDIT), PageAction("remark/$key", "修改备注", PluginIcon.EDIT),
                    PageAction("languages/$key", "包的语言说明", PluginIcon.EDIT),
                    PageAction("update/$key", "更新识别包", PluginIcon.ADD), PageAction("export/$key", "导出", PluginIcon.EXPORT),
                    PageAction("delete/$key", "删除", PluginIcon.CLOSE)))
            ))
        }
        id == "settings" -> PluginPage(id, "流式识别设置", listOf(PageBlock.Text("推理线程：${store.threads}"),
            PageBlock.Text("修改或切换识别包前，请先停止识别。编辑语言说明只影响列表筛选，识别语言取决于所用的识别包。"),
            PageBlock.Actions(listOf(PageAction("threads", "推理线程", PluginIcon.SETTINGS), PageAction("packs", "识别包管理", PluginIcon.MIC)))))
        else -> null
    }
}

internal fun OnlineArchitecture.label() = when (this) {
    OnlineArchitecture.TRANSDUCER -> "在线 Transducer"
    OnlineArchitecture.PARAFORMER -> "在线 Paraformer"
    OnlineArchitecture.ZIPFORMER_CTC -> "在线 Zipformer CTC"
    OnlineArchitecture.NEMO_CTC -> "在线 NeMo CTC"
}
