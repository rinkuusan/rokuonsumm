package com.rokuonsumm.transcription

import com.rokuonsumm.data.db.AppDatabase
import com.rokuonsumm.data.db.SpeakerProfileEntity
import com.rokuonsumm.data.db.TranscriptEntity

/**
 * 発話の話者を命名する (Phase 1b)。
 *
 *  - 「人物N」(自動クラスタ): クラスタを newName にリネーム(isAuto=false 昇格) +
 *     人物N の発話を一括リラベル + 旧クラスタ行を削除
 *  - 「不明」/その他: その発話の声紋から named プロファイルを作成し、その発話をリラベル
 *
 * 共通: 声紋を持つ「不明」発話のうち newName プロファイルにコサイン一致するものを
 *       まとめてリラベルする(命名の磁石効果 — 過去の同一人物の発話が一気に名前付く)。
 *
 * @return リラベルした発話数
 */
object SpeakerNamer {

    suspend fun name(db: AppDatabase, t: TranscriptEntity, rawName: String, threshold: Float): Int {
        val newName = rawName.trim()
        if (newName.isEmpty()) return 0
        val sdao = db.speakerProfileDao()
        val tdao = db.transcriptDao()
        val oldLabel = t.speakerLabel
        val all = sdao.getAll()

        // 命名の種となる声紋: 人物Nならクラスタ重心、それ以外はこの発話の声紋
        val seed: FloatArray = (
            if (oldLabel != null && oldLabel.startsWith("人物"))
                all.firstOrNull { it.name == oldLabel }?.embeddingFloats()
            else
                t.speakerEmbedding?.let { SpeakerProfileEntity.bytesToFloats(it) }
            ) ?: return 0  // 声紋なし → 命名不可

        // newName プロファイルを作成 / 既存なら声紋をマージ
        val existing = all.firstOrNull { it.name == newName }
        val profileEmb = if (existing != null) {
            val ex = existing.embeddingFloats()
            val combined = FloatArray(SpeakerEmbedder.EMBED_DIM) { ex[it] + seed[it] }
            SpeakerEmbedder.average(listOf(combined)) ?: seed
        } else seed
        sdao.upsert(
            SpeakerProfileEntity(
                name = newName,
                embedding = SpeakerProfileEntity.floatsToBytes(profileEmb),
                sampleCount = (existing?.sampleCount ?: 0) + 1,
                isAuto = false,
                occurrences = existing?.occurrences ?: 0
            )
        )

        var relabeled = 0
        if (oldLabel != null && oldLabel.startsWith("人物")) {
            relabeled += tdao.relabelSpeaker(oldLabel, newName)
            if (oldLabel != newName) sdao.delete(oldLabel)
        } else {
            tdao.setSpeakerLabel(t.id, newName); relabeled++
        }

        // 磁石: 声紋付き「不明」発話で newName に一致するものをリラベル
        for (r in tdao.getEmbeddedRows()) {
            if (r.speakerLabel != VadGate.UNKNOWN) continue
            val emb = r.speakerEmbedding ?: continue
            val e = SpeakerProfileEntity.bytesToFloats(emb)
            if (SpeakerEmbedder.cosine(e, profileEmb) >= threshold) {
                tdao.setSpeakerLabel(r.id, newName); relabeled++
            }
        }
        return relabeled
    }
}
