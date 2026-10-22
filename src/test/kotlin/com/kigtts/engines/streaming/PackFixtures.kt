package com.kigtts.engines.streaming

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

internal fun fixture(directory: File, language: String = "zh", name: String = "识别包", model: String = "model"): File {
    val source = File(directory, "source-${java.util.UUID.randomUUID()}.kigasr")
    val manifest = JSONObject().put("format", 1).put("name", name).put("languages", org.json.JSONArray(listOf(language)))
        .put("architecture", "TRANSDUCER").put("files", JSONObject().put("encoder", "encoder.onnx").put("decoder", "decoder.onnx").put("joiner", "joiner.onnx").put("tokens", "tokens.txt"))
    zip(source, mapOf("asr-pack.json" to manifest.toString(), "encoder.onnx" to model,
        "decoder.onnx" to "decoder", "joiner.onnx" to "joiner", "tokens.txt" to "tokens", "LICENSE" to "fixture license"))
    return source
}
internal fun zip(output: File, files: Map<String, String>) {
    ZipOutputStream(output.outputStream()).use { archive -> files.forEach { (name, value) ->
        archive.putNextEntry(ZipEntry(name)); archive.write(value.toByteArray()); archive.closeEntry()
    } }
}
