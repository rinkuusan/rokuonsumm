package com.rokuonsumm.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteOrder

/**
 * m4a/AAC ファイルを PCM16 mono にデコードする。
 * 録音は 16kHz mono なのでデコード結果もそのまま 16kHz mono になる想定。
 *
 * AudioAnalyzer と同じ MediaCodec パターンだが、RMS窓ではなく生サンプル列を返す。
 */
object PcmDecoder {

    data class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    /** デコード失敗時は null */
    fun decode(filePath: String): Pcm? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(filePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 生PCMをプリミティブShortArrayに直接書く(ArrayList<Short>はboxingで
            // 長尺ファイルだとヒープを食い潰してOOMする — 60分で約920MBになっていた)。
            // 尺から必要サイズを見積もって確保し、上限でtruncateして暴走を防ぐ。
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            val estimate = if (durationUs > 0)
                ((durationUs / 1_000_000.0) * sampleRate + sampleRate).toInt() else (1 shl 20)
            var out = ShortArray(estimate.coerceIn(1 shl 16, MAX_SAMPLES))
            var n = 0
            var truncated = false
            var inputDone = false
            val info = MediaCodec.BufferInfo()

            loop@ while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
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
                    val sb = buf.asShortBuffer()
                    if (channelCount <= 1) {
                        while (sb.hasRemaining()) {
                            if (n >= out.size) out = out.copyOf(minOf(out.size + out.size / 2, MAX_SAMPLES))
                            if (n >= MAX_SAMPLES) { truncated = true; break }
                            out[n++] = sb.get()
                        }
                    } else {
                        // 多chは平均してmono化
                        while (sb.remaining() >= channelCount) {
                            var acc = 0
                            repeat(channelCount) { acc += sb.get().toInt() }
                            if (n >= out.size) out = out.copyOf(minOf(out.size + out.size / 2, MAX_SAMPLES))
                            if (n >= MAX_SAMPLES) { truncated = true; break }
                            out[n++] = (acc / channelCount).toShort()
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break@loop
                    if (truncated) break@loop
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    Thread.sleep(3)
                }
            }
            codec.stop(); codec.release()
            if (truncated) Log.w(TAG, "decode truncated at $MAX_SAMPLES samples ($filePath)")

            Pcm(if (n == out.size) out else out.copyOf(n), sampleRate, 1)
        } catch (e: Exception) {
            Log.e(TAG, "decode failed: $filePath", e)
            null
        } finally {
            extractor.release()
        }
    }

    private const val TAG = "PcmDecoder"
    /** デコードPCMの上限サンプル数 (約20分@16kHz=40MB)。これ以上は打ち切ってOOM回避 */
    private const val MAX_SAMPLES = 20_000_000
}
