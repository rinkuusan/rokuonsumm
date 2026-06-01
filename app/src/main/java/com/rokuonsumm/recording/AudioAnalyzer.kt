package com.rokuonsumm.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

object AudioAnalyzer {

    const val WINDOW_MS = 100
    const val SILENCE_THRESHOLD = 0.015f

    data class VoiceSegment(val startMs: Int, val endMs: Int)

    /** Returns (amplitudePerWindow, voiceSegments). Empty array on error. */
    fun analyzeFile(filePath: String): Pair<FloatArray, List<VoiceSegment>> {
        val amps = computeAmplitudes(filePath)
        return amps to detectVoice(amps)
    }

    private fun computeAmplitudes(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return FloatArray(0)

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val samplesPerWindow = sampleRate * WINDOW_MS / 1000
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val result = mutableListOf<Float>()
            var sumSq = 0.0
            var count = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()

            outer@ while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 2_000L)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.order(ByteOrder.LITTLE_ENDIAN)
                    val shortBuf = buf.asShortBuffer()
                    while (shortBuf.hasRemaining()) {
                        var mono = 0.0
                        repeat(channelCount) { mono += shortBuf.get().toDouble() }
                        mono /= channelCount
                        sumSq += mono * mono
                        count++
                        if (count >= samplesPerWindow) {
                            result.add(
                                (Math.sqrt(sumSq / samplesPerWindow) / 32768.0).toFloat()
                            )
                            sumSq = 0.0
                            count = 0
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@outer
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // give it a few more tries before giving up
                    Thread.sleep(5)
                }
            }

            codec.stop()
            codec.release()
            result.toFloatArray()
        } catch (_: Exception) {
            FloatArray(0)
        } finally {
            extractor.release()
        }
    }

    private fun detectVoice(amps: FloatArray): List<VoiceSegment> {
        if (amps.isEmpty()) return emptyList()
        val segments = mutableListOf<VoiceSegment>()
        var voiceStart = -1
        var silenceRun = 0
        val minSilenceWindows = 3 // 300ms silence closes a segment

        amps.forEachIndexed { i, amp ->
            if (amp > SILENCE_THRESHOLD) {
                if (voiceStart < 0) voiceStart = i
                silenceRun = 0
            } else {
                if (voiceStart >= 0) {
                    silenceRun++
                    if (silenceRun >= minSilenceWindows) {
                        segments += VoiceSegment(
                            voiceStart * WINDOW_MS,
                            (i - silenceRun) * WINDOW_MS
                        )
                        voiceStart = -1
                        silenceRun = 0
                    }
                }
            }
        }
        if (voiceStart >= 0) {
            segments += VoiceSegment(voiceStart * WINDOW_MS, amps.size * WINDOW_MS)
        }
        return segments
    }
}
