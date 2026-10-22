package com.kigtts.engines.streaming

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncrementalAsrEngineTest {
    private lateinit var root: File; private lateinit var store: RecognitionPackStore
    @Before fun setup() { root = Files.createTempDirectory("online-asr-test").toFile(); store = RecognitionPackStore(PluginStorage(root)); store.import(fixture(root)) }
    @After fun cleanup() { root.deleteRecursively() }
    private fun audio() = PcmAudio(ByteArray(640).also { it[0] = 1 })
    private class Decoder(private val endpointAt: Int = Int.MAX_VALUE) : OnlineSession {
        var count = 0; var pending = false; var closed = false; var finished = false; var value = ""; var resets = 0; var largestInput = 0
        override fun accept(samples: FloatArray) { largestInput = maxOf(largestInput, samples.size); if (samples.any { it != 0f }) { count++; pending = true } }
        override fun ready() = pending
        override fun decode() { pending = false; value = if (count % 2 == 1) "你" else "你好" }
        override fun text() = value
        override fun endpoint() = count > 0 && count % endpointAt == 0
        override fun reset() { resets++; value = "" }
        override fun finishInput() { finished = true }
        override fun close() { closed = true }
    }
    @Test fun realHypothesisEventsArriveBeforeEofAndTailIsFinalized() = runBlocking {
        val decoder = Decoder(); val engine = IncrementalAsrEngine(store) { _, _, _ -> decoder }
        val events = java.util.concurrent.CopyOnWriteArrayList<RecognitionEvent>(); val partialSeen = CompletableDeferred<Unit>()
        engine.recognizeStream(flow {
            emit(audio()); withTimeout(3_000) { partialSeen.await() }; assertFalse(decoder.finished)
            emit(audio())
        }).collect { events += it; if (it == RecognitionEvent.Partial("你")) partialSeen.complete(Unit) }
        assertTrue(events.any { it == RecognitionEvent.Partial("你好") })
        assertEquals(RecognitionEvent.Final(RecognitionResult("你好")), events.last()); assertTrue(decoder.closed); assertTrue(decoder.finished)
    }
    @Test fun endpointsFinalizeAndResetWithoutDuplicateTrailingFinal() = runBlocking {
        val decoder = Decoder(2); val engine = IncrementalAsrEngine(store) { _, _, _ -> decoder }
        val events = engine.recognizeStream(flow { repeat(4) { emit(audio()) } }).toList()
        assertEquals(2, events.filterIsInstance<RecognitionEvent.Final>().size); assertEquals(2, decoder.resets)
        assertEquals(2, events.filterIsInstance<RecognitionEvent.Partial>().size); assertTrue(decoder.closed); assertFalse(decoder.finished)
    }
    @Test fun cancellationReleasesNativeObjectsAndAllowsAnotherSession() = runBlocking {
        val opened = mutableListOf<Decoder>(); val engine = IncrementalAsrEngine(store) { _, _, _ -> Decoder().also { opened += it } }
        withTimeout(3_000) { engine.recognizeStream(flow { emit(audio()); awaitCancellation() }).first { it is RecognitionEvent.Partial } }
        assertTrue(opened.single().closed); assertFalse(store.session.isLocked)
        assertEquals("你", engine.recognize(audio()).text); assertTrue(opened.last().closed)
    }
    @Test fun silenceEmptyInputAndFailuresDoNotFabricateTextOrRetainSession() = runBlocking {
        val opened = mutableListOf<Decoder>(); val engine = IncrementalAsrEngine(store) { _, _, _ -> Decoder().also { opened += it } }
        assertEquals(listOf(RecognitionEvent.Started), engine.recognizeStream(flowOf(PcmAudio(ByteArray(640)))).toList())
        assertEquals("", engine.recognize(PcmAudio(byteArrayOf())).text)
        try { engine.recognizeStream(flowOf(PcmAudio(byteArrayOf(0, 0), 8_000))).toList(); fail("Bad format accepted") }
        catch (expected: EngineException) { assertEquals(EngineError.UNSUPPORTED_FORMAT, expected.code) }
        assertTrue(opened.all { it.closed }); assertFalse(store.session.isLocked)
    }
    @Test fun fullRecordingUsesSameOnlineBackendWithoutSentenceEndpointing() = runBlocking {
        var endpoints: Boolean? = null; val decoder = Decoder(1)
        val engine = IncrementalAsrEngine(store) { _, _, enabled -> endpoints = enabled; decoder }
        assertEquals("你", engine.recognize(audio()).text); assertEquals(false, endpoints); assertTrue(decoder.finished); assertTrue(decoder.closed)
        assertEquals(StreamingKind.INCREMENTAL, engine.info.streaming)
    }
    @Test fun unbrokenSpeechHasBoundedUtterancesEvenWhenBackendEndpointNeverFires() = runBlocking {
        val decoder = Decoder(); var created = 0
        val engine = IncrementalAsrEngine(store) { _, _, _ -> created++; decoder }
        val longChunk = PcmAudio(ByteArray(16_000 * 2 * 61) { if (it % 2 == 0) 1 else 0 })
        var finals = 0; var partials = 0
        engine.recognizeStream(flowOf(longChunk)).collect {
            if (it is RecognitionEvent.Final) { finals++; assertTrue(it.result.text.isNotBlank()) }
            if (it is RecognitionEvent.Partial) partials++
        }
        assertEquals(3, finals); assertEquals(2, decoder.resets); assertEquals(1, created)
        assertTrue(partials > 100); assertTrue(decoder.largestInput <= 16_000 * 3 / 10)
        assertTrue(decoder.finished); assertTrue(decoder.closed)
    }
    @Test fun sentenceBoundaryKeepsDecoderAndModelForLongListeningWithoutRereadingOldAudio() = runBlocking {
        val decoder = Decoder(5); var created = 0; var finals = 0
        val engine = IncrementalAsrEngine(store) { _, _, _ -> created++; decoder }
        engine.recognizeStream(flow { repeat(10_000) { emit(audio()) } }).collect {
            if (it is RecognitionEvent.Final) finals++
        }
        assertEquals(2_000, finals); assertEquals(2_000, decoder.resets); assertEquals(1, created)
        assertEquals(10_000, decoder.count); assertFalse(decoder.finished); assertTrue(decoder.closed)
    }
    @Test fun revisedEmptyHypothesisClearsThePreviouslyShownPartial() = runBlocking {
        var count = 0; var pending = false; var closed = false
        val decoder = object : OnlineSession {
            override fun accept(samples: FloatArray) { if (samples.any { it != 0f }) { count++; pending = true } }
            override fun ready() = pending
            override fun decode() { pending = false }
            override fun text() = if (count == 1) "临时" else ""
            override fun endpoint() = false
            override fun reset() {}
            override fun finishInput() {}
            override fun close() { closed = true }
        }
        val events = IncrementalAsrEngine(store) { _, _, _ -> decoder }
            .recognizeStream(flowOf(audio(), audio())).toList()
        assertEquals(listOf(RecognitionEvent.Started, RecognitionEvent.Partial("临时"), RecognitionEvent.Partial("")), events)
        assertTrue(closed)
    }
}
