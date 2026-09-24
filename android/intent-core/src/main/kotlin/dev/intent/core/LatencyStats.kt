package dev.intent.core

/** Detection-latency summary for the Phase 0 GO/NO-GO gate (PRD §61: median < 2s, p95 < 4s). */
data class LatencySummary(
    val count: Int,
    val medianMs: Long?,
    val p95Ms: Long?,
    val maxMs: Long?,
) {
    fun meetsGoThreshold(medianLimitMs: Long = 2_000, p95LimitMs: Long = 4_000): Boolean? {
        if (medianMs == null || p95Ms == null) return null
        return medianMs < medianLimitMs && p95Ms < p95LimitMs
    }

    companion object {
        /** Nearest-rank percentiles; no interpolation, so every reported value was observed. */
        fun of(samplesMs: Collection<Long>): LatencySummary {
            if (samplesMs.isEmpty()) return LatencySummary(0, null, null, null)
            val sorted = samplesMs.sorted()
            return LatencySummary(
                count = sorted.size,
                medianMs = percentile(sorted, 50.0),
                p95Ms = percentile(sorted, 95.0),
                maxMs = sorted.last(),
            )
        }

        internal fun percentile(sorted: List<Long>, p: Double): Long {
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
