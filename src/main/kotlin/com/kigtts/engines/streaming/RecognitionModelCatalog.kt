package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*

/** Fixed official release assets checked during development. No model weights ship in the plugin. */
internal data class CatalogModel(val id: String, val title: String, val languages: List<String>, val archive: String,
    val sha256: String, val bytes: Long) {
    val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$archive.tar.bz2"
}
internal object RecognitionModelCatalog {
    val models = listOf(
        CatalogModel("zh14m", "中文（轻量）", listOf("zh"), "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            "2cbd71b640d9c37d3784f29367333a4577b0398b62e9deeed418170b081cba8b", 74004050),
        CatalogModel("en20m", "英文（轻量）", listOf("en"), "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9", 127887156),
        CatalogModel("zh-en", "中文与英文（双语）", listOf("zh", "en"), "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16",
            "2b7c63322b32e5e0f2526043a1103366119ca58dd615cd7105a37c01db9553d7", 458187351))
    fun page() = PluginPage("catalog", "获取识别包", listOf(
        PageBlock.Text("选择需要的语言包。下载需要网络和对应存储空间；支持的语言由包本身决定。"),
        PageBlock.Group("其它来源", listOf(
            PageBlock.Input("自定义下载地址", "", "download-custom", icon = PluginIcon.ADD)))
    ), management = ManagementList("recognition-catalog", models.map { model ->
        ManagementItem(model.id, model.title, "${model.languages.joinToString(" / ")} · 下载约 ${model.bytes / 1024 / 1024} MiB", PluginIcon.MIC,
            primaryAction = "catalog-download/${model.id}", actions = listOf(PageAction("catalog-download/${model.id}", "下载并安装", PluginIcon.ADD)))
    }))
}
