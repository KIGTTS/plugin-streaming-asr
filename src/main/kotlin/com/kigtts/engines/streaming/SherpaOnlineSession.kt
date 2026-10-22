package com.kigtts.engines.streaming

import com.k2fsa.sherpa.onnx.*

/** This adapter owns all JNI objects. Host code receives only protocol events. */
internal interface OnlineSession : AutoCloseable {
    fun accept(samples: FloatArray)
    fun ready(): Boolean
    fun decode()
    fun text(): String
    fun endpoint(): Boolean
    fun reset()
    fun finishInput()
}

internal class SherpaOnlineSession(pack: RecognitionPack, threads: Int, endpoints: Boolean) : OnlineSession {
    private val model = OnlineModelConfig(tokens = pack.file("tokens").path, numThreads = threads, provider = "cpu", modelType = pack.modelType).apply {
        when (pack.architecture) {
            OnlineArchitecture.TRANSDUCER -> transducer = OnlineTransducerModelConfig(pack.file("encoder").path, pack.file("decoder").path, pack.file("joiner").path)
            OnlineArchitecture.PARAFORMER -> paraformer = OnlineParaformerModelConfig(pack.file("encoder").path, pack.file("decoder").path)
            OnlineArchitecture.ZIPFORMER_CTC -> zipformer2Ctc = OnlineZipformer2CtcModelConfig(pack.file("model").path)
            OnlineArchitecture.NEMO_CTC -> neMoCtc = OnlineNeMoCtcModelConfig(pack.file("model").path)
        }
    }
    private val recognizer = OnlineRecognizer(null, OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = pack.sampleRate, featureDim = 80, dither = 0f), modelConfig = model,
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f), rule2 = EndpointRule(true, 1.4f, 0f),
            rule3 = EndpointRule(false, 0f, 20f)),
        enableEndpoint = endpoints, decodingMethod = "greedy_search"))
    private val stream = try { recognizer.createStream() } catch (error: Throwable) { recognizer.release(); throw error }
    private val rate = pack.sampleRate
    override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, rate)
    override fun ready() = recognizer.isReady(stream)
    override fun decode() = recognizer.decode(stream)
    override fun text() = recognizer.getResult(stream).text.trim()
    override fun endpoint() = recognizer.isEndpoint(stream)
    override fun reset() = recognizer.reset(stream)
    override fun finishInput() = stream.inputFinished()
    override fun close() { try { stream.release() } finally { recognizer.release() } }
}
