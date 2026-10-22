package com.kigtts.engines.streaming

import java.io.File

/** Import the documented online layouts only; an offline model is never silently segmented. */
internal object OfficialPackDiscovery {
    fun describe(root: File): RecognitionPack {
        root.walkTopDown().filter { it.isFile && it.name == "asr-pack.json" }.toList().let { manifests ->
            require(manifests.size <= 1) { "一次只能导入一个识别包" }
            manifests.singleOrNull()?.let { return RecognitionPack.read(requireNotNull(it.parentFile)) }
        }
        val tokens = root.walkTopDown().filter { it.isFile && it.name == "tokens.txt" }.toList()
        require(tokens.size == 1) { "需要一个配套的 tokens.txt" }
        val token = tokens.single()
        val tokenDirectory = requireNotNull(token.parentFile)
        val label = tokenDirectory.name.lowercase()
        val origin = (label + "/" + root.walkTopDown().filter { it.isDirectory }.joinToString("/") { it.name.lowercase() })
        require("streaming" in origin) { "请导入官方 streaming 模型，或提供明确的 asr-pack.json；离线模型不能用作增量解码" }
        val languages = when {
            "bilingual" in origin && "zh-en" in origin -> listOf("zh", "en")
            "korean" in origin -> listOf("ko")
            else -> listOf("zh", "en", "fr", "de", "es", "ru", "ja", "ko", "bn").filter { "-$it-" in origin || origin.endsWith("-$it") }
        }
        require(languages.isNotEmpty()) { "无法确定语言，请在 asr-pack.json 中声明 languages" }
        val candidates = root.walkTopDown().filter { it.isFile && it.extension == "onnx" }.toList()
        fun choose(prefix: String) = candidates.filter { it.name.startsWith(prefix) }
            .sortedWith(compareBy<File> { if ("int8" in it.name) 0 else 1 }.thenBy { it.invariantSeparatorsPath }).firstOrNull()
        val encoder = choose("encoder"); val decoder = choose("decoder"); val joiner = choose("joiner")
        val architecture = when {
            "paraformer" in origin -> OnlineArchitecture.PARAFORMER
            "nemo" in origin && "ctc" in origin -> OnlineArchitecture.NEMO_CTC
            "ctc" in origin && "zipformer" in origin -> OnlineArchitecture.ZIPFORMER_CTC
            encoder != null && decoder != null && joiner != null -> OnlineArchitecture.TRANSDUCER
            else -> error("不支持的在线模型布局")
        }
        val selected = mutableMapOf("tokens" to token)
        if (architecture in setOf(OnlineArchitecture.TRANSDUCER, OnlineArchitecture.PARAFORMER)) {
            selected["encoder"] = requireNotNull(encoder); selected["decoder"] = requireNotNull(decoder)
            if (architecture == OnlineArchitecture.TRANSDUCER) selected["joiner"] = requireNotNull(joiner)
        } else selected["model"] = requireNotNull(choose("model"))
        // Empty modelType lets sherpa use the model's ONNX metadata, including Zipformer v1/v2.
        return RecognitionPack(root.name, root, tokenDirectory.name, languages, architecture,
            selected.mapValues { it.value.relativeTo(root).invariantSeparatorsPath })
    }
}
