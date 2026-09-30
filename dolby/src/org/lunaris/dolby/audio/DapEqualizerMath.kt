/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Dolby DAP graphic-equalizer math.
 *
 * Parameter 110 uses signed Q4 dB values: 16 units represent 1 dB. Positive
 * graphic-EQ boost is limited to +10 dB (160 Q4). Attenuation is intentionally
 * not given an artificial product-level floor.
 */
object DapEqualizerMath {
    const val Q4_PER_DB = 16
    const val MAX_BOOST_DB = 10f
    const val MAX_BOOST_Q4 = Q4_PER_DB * 10

    data class HeadroomResult(
        val gainsQ4: IntArray,
        val attenuationQ4: Int,
    ) {
        val attenuationDb: Float
            get() = attenuationQ4.toFloat() / Q4_PER_DB
    }

    fun q4ToDb(value: Int): Float = value.toFloat() / Q4_PER_DB

    fun dbToQ4(valueDb: Double): Int {
        require(valueDb.isFinite()) { "Gain must be finite" }
        return (valueDb * Q4_PER_DB)
            .roundToInt()
            .coerceAtMost(MAX_BOOST_Q4)
    }

    fun clampBoost(valueQ4: Int): Int = valueQ4.coerceAtMost(MAX_BOOST_Q4)

    /**
     * Converts a relative EQ curve into a full-scale-safe DAP curve.
     *
     * DAP's profile GEQ does not expose a separate confirmed preamp control in
     * the Xiaomi wrapper. Leaving positive GEQ gain in a full-scale music path
     * makes the downstream limiter/regulator absorb that gain, which is most
     * audible at high playback volume. Subtracting the positive peak from every
     * band is mathematically equivalent to applying a preamp of -peak dB: the
     * requested frequency-response shape is preserved while the highest band is
     * kept at 0 dB.
     *
     * Curves that are already at or below 0 dB are returned unchanged. No extra
     * safety attenuation is injected here; mondrian's stock DAX tuning remains
     * responsible for endpoint/speaker protection.
     */
    fun withDigitalHeadroom(gainsQ4: IntArray): HeadroomResult {
        if (gainsQ4.isEmpty()) return HeadroomResult(IntArray(0), 0)

        val peakQ4 = gainsQ4.maxOrNull()?.coerceAtLeast(0) ?: 0
        if (peakQ4 == 0) return HeadroomResult(gainsQ4.copyOf(), 0)

        return HeadroomResult(
            gainsQ4 = IntArray(gainsQ4.size) { index -> gainsQ4[index] - peakQ4 },
            attenuationQ4 = peakQ4,
        )
    }

    fun sampleLogFrequency(
        targetHz: Double,
        points: List<Pair<Double, Double>>,
    ): Double {
        require(targetHz > 0.0 && targetHz.isFinite()) { "Invalid target frequency" }
        if (points.size < 2) return 0.0

        val sorted = points.sortedBy { it.first }
        val exact = sorted.firstOrNull { it.first == targetHz }
        if (exact != null) return exact.second
        if (targetHz < sorted.first().first || targetHz > sorted.last().first) return 0.0

        val upperIndex = sorted.indexOfFirst { it.first > targetHz }
        if (upperIndex <= 0) return 0.0
        val lower = sorted[upperIndex - 1]
        val upper = sorted[upperIndex]

        val denominator = ln(upper.first) - ln(lower.first)
        if (denominator == 0.0) return lower.second
        val ratio = (ln(targetHz) - ln(lower.first)) / denominator
        return lower.second + (upper.second - lower.second) * ratio
    }

    fun interpolateQ4(
        targetHz: Int,
        sourceFrequencies: List<Int>,
        sourceGains: List<Int>,
    ): Int {
        require(sourceFrequencies.size == sourceGains.size)
        require(sourceFrequencies.isNotEmpty())

        sourceFrequencies.indexOf(targetHz).takeIf { it >= 0 }?.let {
            return clampBoost(sourceGains[it])
        }

        val upperIndex = sourceFrequencies.indexOfFirst { it > targetHz }
        if (upperIndex < 0) return clampBoost(sourceGains.last())
        if (upperIndex == 0) return clampBoost(sourceGains.first())

        val lowerHz = sourceFrequencies[upperIndex - 1].toDouble()
        val upperHz = sourceFrequencies[upperIndex].toDouble()
        val lowerGain = sourceGains[upperIndex - 1].toDouble()
        val upperGain = sourceGains[upperIndex].toDouble()

        val denominator = ln(upperHz) - ln(lowerHz)
        val ratio = if (denominator == 0.0) 0.0 else
            (ln(targetHz.toDouble()) - ln(lowerHz)) / denominator
        return clampBoost((lowerGain + (upperGain - lowerGain) * ratio).roundToInt())
    }
}
