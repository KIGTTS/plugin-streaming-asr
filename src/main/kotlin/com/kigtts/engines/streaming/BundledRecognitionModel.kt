package com.kigtts.engines.streaming

import java.io.File

/** Installed and verified by the plugin host. The recognizer reads the ONNX files in place. */
internal object BundledRecognitionModel {
    const val ID = "bundled-zh14m"

    fun readFrom(assets: File): RecognitionPack? {
        val directory = File(assets, "models/zh14m")
        if (!directory.exists()) return null
        return RecognitionPack.read(directory).copy(id = ID)
    }
}
