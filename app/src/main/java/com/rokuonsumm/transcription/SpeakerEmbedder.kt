package com.rokuonsumm.transcription

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 3D-Speaker CAM++ で話者埋め込み(192次元)を計算する。
 *
 * 入力: 16kHz mono PCM (ShortArray)
 * 処理: kaldi fbank(80次元) → CMN → CAM++ ONNX → 192次元埋め込み → L2正規化
 *
 * ★ fbank仕様は Python(torchaudio.compliance.kaldi.fbank)と数値完全一致(cos=1.0)を確認済み:
 *   frame=400/shift=160/FFT=512 / DC除去 → preemph0.97 → povey窓(hann^0.85)
 *   / power spectrum / kaldi三角melフィルタ(80bin,20-8000Hz) / log / CMN
 *
 * 使い終わったら close()。
 */
class SpeakerEmbedder(context: Context) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions())
    }

    /**
     * PCM(16kHz mono)から L2正規化済み192次元埋め込みを返す。
     * 短すぎる(最低 MIN_SAMPLES 未満)場合は null。
     */
    fun embed(pcm: ShortArray, sampleRate: Int = 16_000): FloatArray? {
        if (pcm.size < MIN_SAMPLES) return null
        val feat = fbank(pcm) ?: return null          // [T, 80]
        val nFrames = feat.size
        if (nFrames < MIN_FRAMES) return null

        // CMN: 各メル次元の時間平均を引く
        val mean = DoubleArray(N_MEL)
        for (t in 0 until nFrames) for (m in 0 until N_MEL) mean[m] += feat[t][m]
        for (m in 0 until N_MEL) mean[m] /= nFrames
        val flat = FloatArray(nFrames * N_MEL)
        var idx = 0
        for (t in 0 until nFrames) for (m in 0 until N_MEL) flat[idx++] = (feat[t][m] - mean[m]).toFloat()

        return try {
            val input = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(flat), longArrayOf(1, nFrames.toLong(), N_MEL.toLong())
            )
            val out = session.run(mapOf("x" to input))
            @Suppress("UNCHECKED_CAST")
            val emb = (out.get(0).value as Array<FloatArray>)[0]
            input.close(); out.close()
            l2normalize(emb)
        } catch (e: Exception) {
            Log.e(TAG, "speaker embed failed", e)
            null
        }
    }

    // ── kaldi fbank ────────────────────────────────────────────────────

    /** @return [T][80] のlog-melエネルギー (CMN前) */
    private fun fbank(pcm: ShortArray): Array<DoubleArray>? {
        val n = pcm.size
        if (n < FRAME_LEN) return null
        val nFrames = 1 + (n - FRAME_LEN) / FRAME_SHIFT
        val feat = Array(nFrames) { DoubleArray(N_MEL) }
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        val frame = DoubleArray(FRAME_LEN)

        for (f in 0 until nFrames) {
            val off = f * FRAME_SHIFT
            // コピー
            var sum = 0.0
            for (i in 0 until FRAME_LEN) { val s = pcm[off + i].toDouble(); frame[i] = s; sum += s }
            // remove DC (フレーム平均減算)
            val dc = sum / FRAME_LEN
            for (i in 0 until FRAME_LEN) frame[i] -= dc
            // preemphasis 0.97 (先頭は x[0]*(1-0.97))
            var prev = frame[0]
            re[0] = frame[0] * (1.0 - PREEMPH) * povey[0]
            for (i in 1 until FRAME_LEN) {
                val pe = frame[i] - PREEMPH * prev
                prev = frame[i]
                re[i] = pe * povey[i]
            }
            // ゼロパディング + im クリア
            for (i in FRAME_LEN until NFFT) re[i] = 0.0
            java.util.Arrays.fill(im, 0.0)
            // FFT
            fft(re, im)
            // power → mel → log
            for (m in 0 until N_MEL) {
                val mf = melFb[m]
                var e = 0.0
                var k = melStart[m]
                val end = melEnd[m]
                while (k <= end) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    e += mf[k] * p
                    k++
                }
                feat[f][m] = ln(if (e < 1e-10) 1e-10 else e)
            }
        }
        return feat
    }

    /** in-place 反復 radix-2 FFT */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // bit reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curR = 1.0; var curI = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val bR = re[i + k + half]; val bI = im[i + k + half]
                    val tR = bR * curR - bI * curI
                    val tI = bR * curI + bI * curR
                    val aR = re[i + k]; val aI = im[i + k]
                    re[i + k] = aR + tR; im[i + k] = aI + tI
                    re[i + k + half] = aR - tR; im[i + k + half] = aI - tI
                    val ncurR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = ncurR
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun close() { try { session.close() } catch (_: Exception) {} }

    companion object {
        private const val TAG = "SpeakerEmbedder"
        private const val MODEL_ASSET = "campplus.onnx"
        const val EMBED_DIM = 192
        private const val FRAME_LEN = 400
        private const val FRAME_SHIFT = 160
        private const val NFFT = 512
        private const val N_MEL = 80
        private const val PREEMPH = 0.97
        private const val SR = 16_000
        private const val LOW_HZ = 20.0
        private const val HIGH_HZ = 8000.0
        private const val MIN_SAMPLES = SR / 2          // 0.5秒未満は無効
        private const val MIN_FRAMES = 30

        // povey窓 = (0.5 - 0.5cos(2πn/(N-1)))^0.85
        private val povey: DoubleArray = DoubleArray(FRAME_LEN) { i ->
            Math.pow(0.5 - 0.5 * cos(2.0 * Math.PI * i / (FRAME_LEN - 1)), 0.85)
        }

        // kaldi melフィルタバンク (80 × 257) を事前計算。非ゼロ範囲も持つ。
        private const val N_BIN = NFFT / 2 + 1          // 257
        private val melFb: Array<DoubleArray>
        private val melStart: IntArray
        private val melEnd: IntArray

        private fun hz2mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

        init {
            val fb = Array(N_MEL) { DoubleArray(N_BIN) }
            val st = IntArray(N_MEL) { N_BIN }
            val en = IntArray(N_MEL) { -1 }
            val binW = SR.toDouble() / NFFT
            val mlo = hz2mel(LOW_HZ); val mhi = hz2mel(HIGH_HZ)
            val delta = (mhi - mlo) / (N_MEL + 1)
            for (b in 0 until N_MEL) {
                val lmel = mlo + b * delta
                val cmel = mlo + (b + 1) * delta
                val rmel = mlo + (b + 2) * delta
                for (k in 0 until N_BIN) {
                    val mel = hz2mel(binW * k)
                    if (mel > lmel && mel < rmel) {
                        val w = if (mel <= cmel) (mel - lmel) / (cmel - lmel)
                                else (rmel - mel) / (rmel - cmel)
                        fb[b][k] = w
                        if (k < st[b]) st[b] = k
                        if (k > en[b]) en[b] = k
                    }
                }
                if (en[b] < st[b]) { st[b] = 0; en[b] = 0 }  // 空フィルタ保険
            }
            melFb = fb; melStart = st; melEnd = en
        }

        private fun l2normalize(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val norm = sqrt(s).toFloat() + 1e-9f
            return FloatArray(v.size) { v[it] / norm }
        }

        /** コサイン類似度 (両方L2正規化済み前提なら内積でOKだが、念のため正規化) */
        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return (dot / (sqrt(na) * sqrt(nb) + 1e-9)).toFloat()
        }

        /** 複数埋め込みの平均(プロファイル登録用)→ L2正規化 */
        fun average(embs: List<FloatArray>): FloatArray? {
            if (embs.isEmpty()) return null
            val dim = embs[0].size
            val acc = FloatArray(dim)
            for (e in embs) for (i in 0 until dim) acc[i] += e[i]
            for (i in 0 until dim) acc[i] /= embs.size
            return l2normalize(acc)
        }
    }
}
