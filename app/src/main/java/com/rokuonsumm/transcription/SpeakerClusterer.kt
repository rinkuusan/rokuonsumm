package com.rokuonsumm.transcription

import android.content.Context
import com.rokuonsumm.OpLog
import com.rokuonsumm.data.db.SpeakerProfileDao
import com.rokuonsumm.data.db.SpeakerProfileEntity

/**
 * 自動話者クラスタリング (Phase 1)。
 *
 * VadGate が「登録済み(named)話者」と照合し、マッチしなければ代表声紋を返す。
 * ここではその声紋を「自動クラスタ(人物1, 人物2…)」に振り分ける:
 *  - 既存の自動クラスタにコサイン一致 → 重心を更新し occurrences++
 *  - どれにも一致せず → 新規クラスタ作成 (上限 MAX_AUTO まで)
 *  - occurrences >= MIN_OCC で初めて「人物N」として表示昇格。未満は "不明" のままプール。
 *    (TV/通行人など一見さんの声で画面が埋まるのを防ぐ頻度ゲート)
 *
 * ユーザが UtteranceDetail から命名すると isAuto=false の通常プロファイルに昇格する。
 */
object SpeakerClusterer {

    private const val MIN_OCC = 3     // この回数 再登場したら「人物N」に昇格
    private const val MAX_AUTO = 200  // 自動クラスタ数の上限 (爆発防止)

    /**
     * @param namedLabel VadGate の登録話者照合結果 (話者名 / "不明" / null)
     * @param embedding  セグメント代表声紋 (L2正規化済み 192次元)
     * @return 最終表示ラベル (話者名 / "人物N" / "不明")
     */
    suspend fun resolve(
        ctx: Context,
        dao: SpeakerProfileDao,
        namedLabel: String?,
        embedding: FloatArray,
        threshold: Float
    ): String {
        // 登録済み話者と判定済みなら確定
        if (namedLabel != null && namedLabel != VadGate.UNKNOWN) return namedLabel

        val autos = dao.getAll().filter { it.isAuto }
        val nearest = autos.maxByOrNull { SpeakerEmbedder.cosine(embedding, it.embeddingFloats()) }
        val sim = nearest?.let { SpeakerEmbedder.cosine(embedding, it.embeddingFloats()) } ?: -1f

        if (nearest != null && sim >= threshold) {
            val updated = mergeInto(nearest, embedding)
            dao.upsert(updated)
            return if (updated.occurrences >= MIN_OCC) updated.name else VadGate.UNKNOWN
        }

        if (autos.size < MAX_AUTO) {
            val name = nextAutoName(autos)
            dao.upsert(
                SpeakerProfileEntity(
                    name = name,
                    embedding = SpeakerProfileEntity.floatsToBytes(embedding),
                    sampleCount = 1,
                    isAuto = true,
                    occurrences = 1
                )
            )
            OpLog.i(ctx, "speaker.new_cluster", "name=$name (auto=${autos.size + 1})")
        }
        return VadGate.UNKNOWN
    }

    /** 既存クラスタ重心に新声紋を重み付き平均で混ぜる (occurrences で重み付け) */
    private fun mergeInto(p: SpeakerProfileEntity, emb: FloatArray): SpeakerProfileEntity {
        val old = p.embeddingFloats()
        val oc = p.occurrences.coerceAtLeast(1)
        val combined = FloatArray(SpeakerEmbedder.EMBED_DIM) { old[it] * oc + emb[it] }
        val newCentroid = SpeakerEmbedder.average(listOf(combined)) ?: old
        return p.copy(
            embedding = SpeakerProfileEntity.floatsToBytes(newCentroid),
            sampleCount = p.sampleCount + 1,
            occurrences = p.occurrences + 1
        )
    }

    /** 「人物1」「人物2」… の次の空き番号 */
    private fun nextAutoName(autos: List<SpeakerProfileEntity>): String {
        val used = autos.mapNotNull { it.name.removePrefix("人物").toIntOrNull() }.toSet()
        var n = 1
        while (n in used) n++
        return "人物$n"
    }
}
