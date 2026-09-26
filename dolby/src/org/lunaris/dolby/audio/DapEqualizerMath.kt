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

    fun q4ToDb(value: Int): Float = value.toFloat() / Q4_PER_DB

    fun dbToQ4(valueDb: Double): Int {
        require(valueDb.isFinite()) { "Gain must be finite" }
        return (valueDb * Q4_PER_DB)
            .roundToInt()
            .coerceAtMost(MAX_BOOST_Q4)
    }

    fun clampBoost(valueQ4: Int): Int = valueQ4.coerceAtMost(MAX_BOOST_Q4)

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
