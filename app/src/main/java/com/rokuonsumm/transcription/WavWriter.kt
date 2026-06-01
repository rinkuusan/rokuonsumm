package com.rokuonsumm.transcription

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM16 mono のサンプル列を WAV ファイルに書き出す。
 * Groq の transcription API は WAV を受け付ける。
 */
object WavWriter {

    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        val byteRate = sampleRate * 2  // mono, 16bit
        val dataSize = samples.size * 2
        FileOutputStream(file).use { fos ->
            fos.channel.use { ch ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(36 + dataSize)         // ファイルサイズ - 8
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16)                    // fmtチャンクサイズ
                header.putShort(1)                   // PCM
                header.putShort(1)                   // mono
                header.putInt(sampleRate)
                header.putInt(byteRate)
                header.putShort(2)                   // block align (mono*16bit/8)
                header.putShort(16)                  // bits per sample
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(dataSize)
                header.flip()
                ch.write(header)

                // サンプルを 64KB ずつ LE で書く
                val chunk = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < samples.size) {
                    chunk.clear()
                    val n = minOf(samples.size - i, chunk.capacity() / 2)
                    for (k in 0 until n) chunk.putShort(samples[i + k])
                    chunk.flip()
                    ch.write(chunk)
                    i += n
                }
            }
        }
    }
}
