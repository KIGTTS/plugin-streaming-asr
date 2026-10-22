package com.kigtts.engines.streaming

import org.json.JSONObject
import org.json.JSONArray
import java.io.File

internal enum class OnlineArchitecture { TRANSDUCER, PARAFORMER, ZIPFORMER_CTC, NEMO_CTC }

/** An explicit online model declaration. Language names describe the model, not a forced decoder locale. */
internal data class RecognitionPack(val id: String, val directory: File, val name: String, val languages: List<String>,
    val architecture: OnlineArchitecture, val files: Map<String, String>, val modelType: String = "",
    val sampleRate: Int = 16_000, val remark: String = "", val pinned: Boolean = false, val order: Long = 0) {
    fun file(role: String): File = safePackFile(directory, requireNotNull(files[role]))
    fun json() = JSONObject().put("format", 1).put("name", name).put("languages", JSONArray(languages))
        .put("architecture", architecture.name).put("files", JSONObject(files)).put("modelType", modelType)
        .put("sampleRate", sampleRate).put("remark", remark).put("pinned", pinned).put("order", order)
    companion object {
        fun read(directory: File): RecognitionPack {
            val meta = File(directory, "asr-pack.json")
            require(meta.isFile && meta.length() <= 64 * 1024) { "缺少识别包说明 asr-pack.json" }
            val json = JSONObject(meta.readText())
            require(json.getInt("format") == 1) { "不支持的识别包格式" }
            val architecture = OnlineArchitecture.valueOf(json.getString("architecture"))
            val roles = when (architecture) {
                OnlineArchitecture.TRANSDUCER -> listOf("encoder", "decoder", "joiner", "tokens")
                OnlineArchitecture.PARAFORMER -> listOf("encoder", "decoder", "tokens")
                else -> listOf("model", "tokens")
            }
            val declared = json.getJSONObject("files")
            val files = roles.associateWith { declared.getString(it) }
            files.forEach { (role, path) -> require(safePackFile(directory, path).let { it.isFile && it.length() > 0 }) { "缺少 $role 文件" } }
            val languages = json.getJSONArray("languages").let { array -> (0 until array.length()).map { array.getString(it) } }
            require(languages.isNotEmpty() && languages.size <= 32 && languages.distinct().size == languages.size)
            require(languages.all { it.matches(Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8})*")) }) { "请用 zh、en、ko 等语言代码" }
            val name = json.getString("name").trim(); require(name.isNotBlank() && name.length <= 120)
            val rate = json.optInt("sampleRate", 16_000); require(rate in 8_000..48_000)
            val modelType = json.optString("modelType"); require(modelType in setOf("", "zipformer", "zipformer2", "lstm", "conformer", "paraformer"))
            val remark = json.optString("remark"); require(remark.length <= 4_000)
            return RecognitionPack(directory.name, directory, name, languages, architecture, files, modelType, rate,
                remark, json.optBoolean("pinned"), json.optLong("order"))
        }
    }
}

internal fun safePackFile(root: File, path: String): File {
    require(path.isNotBlank() && !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && !path.startsWith('/'))
    require(path.split('/').none { it in setOf("", ".", "..") }) { "识别包路径不合法" }
    return File(root, path).canonicalFile.also { require(it.toPath().startsWith(root.canonicalFile.toPath())) }
}
