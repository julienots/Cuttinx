package com.superessence.cuttinx.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.superessence.cuttinx.core.audio.DspChain
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bridges the pure-Kotlin DSP chain of a clip (EQ, compressor, reverb, voice effects, fades,
 * volume/pan automation) into the Media3 audio pipeline. Works on 16-bit or float PCM.
 */
@UnstableApi
class ClipAudioProcessor(
    private val chainFactory: () -> DspChain,
    /** Clip duration on the timeline, used to validate the stream position after a seek. */
    private val clipDurationUs: Long,
    private val candidateOffsetsUs: List<Long> = emptyList(),
) : BaseAudioProcessor() {
    private var chain: DspChain? = null
    private var framePos = 0L
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val fmt = inputAudioFormat
        chain = chainFactory().also { it.configure(fmt.sampleRate, fmt.channelCount) }
        // Position (in the clip's timeline) from which input resumes. Several conventions are
        // possible depending on the pipeline: keep the first plausible one.
        val pos = streamMetadata.positionOffsetUs
        val rel = (listOf(pos) + candidateOffsetsUs.map { pos - it }).firstOrNull { it in 0..(clipDurationUs + 500_000) } ?: 0L
        framePos = rel * fmt.sampleRate / 1_000_000L
    }

    override fun onReset() { chain = null; framePos = 0 }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val fmt = inputAudioFormat
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val c = chain ?: chainFactory().also { it.configure(fmt.sampleRate, fmt.channelCount); chain = it }
        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val samples = if (float) remaining / 4 else remaining / 2
        val frames = samples / fmt.channelCount
        if (scratch.size < samples) scratch = FloatArray(samples)
        val inOrder = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) { val fb = inOrder.asFloatBuffer(); fb.get(scratch, 0, samples) }
        else { val sb = inOrder.asShortBuffer(); for (i in 0 until samples) scratch[i] = sb.get(i) / 32768f }
        inputBuffer.position(inputBuffer.position() + remaining)
        c.process(scratch, frames, framePos)
        framePos += frames
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        if (float) { for (i in 0 until samples) out.putFloat(scratch[i]) }
        else { for (i in 0 until samples) out.putShort((scratch[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
        out.flip()
    }
}
