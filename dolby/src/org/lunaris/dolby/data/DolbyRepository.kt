/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.DolbyConstants.DsParam
import org.lunaris.dolby.R
import org.lunaris.dolby.audio.DolbyAudioEffect
import org.lunaris.dolby.audio.DapEqualizerMath
import org.lunaris.dolby.domain.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DolbyRepository private constructor(private val context: Context) : AutoCloseable {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var dolbyEffect = createDolbyEffect()
    
    private val defaultPrefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    private val presetsPrefs = context.getSharedPreferences(DolbyConstants.PREF_FILE_PRESETS, Context.MODE_PRIVATE)
    
    private val _isOnSpeaker = MutableStateFlow(checkIsOnSpeaker())
    val isOnSpeaker: StateFlow<Boolean> = _isOnSpeaker.asStateFlow()
    
    private val _currentProfile = MutableStateFlow(0)
    val currentProfile: StateFlow<Int> = _currentProfile.asStateFlow()

    val stereoWideningSupported = context.resources.getBoolean(R.bool.dolby_stereo_widening_supported)
    val volumeLevelerSupported = context.resources.getBoolean(R.bool.dolby_volume_leveler_supported)
    
    private var isReleased = false
    
    private var cachedPresets: List<EqualizerPreset>? = null
    private val presetCacheLock = Any()

    private fun createDolbyEffect(): DolbyAudioEffect {
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to create Dolby effect: ${e.message}")
            throw e
        }
    }

    @Synchronized
    private fun checkEffect() {
        if (isReleased) {
            DolbyConstants.dlog(TAG, "Repository released, skipping effect check")
            return
        }
        
        try {
            if (!dolbyEffect.hasControl()) {
                DolbyConstants.dlog(TAG, "Lost audio effect control, recreating")
                dolbyEffect.release()
                dolbyEffect = createDolbyEffect()
                restoreSavedProfileIfNeeded()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking effect: ${e.message}")
        }
    }

    private fun readSavedProfile(): Int? {
        return defaultPrefs.getString(DolbyConstants.PREF_PROFILE, null)
            ?.toIntOrNull()
    }

    private fun restoreSavedProfileIfNeeded() {
        val savedProfile = readSavedProfile() ?: return
        if (dolbyEffect.profile != savedProfile) {
            dolbyEffect.profile = savedProfile
        }
        restoreProfilePreset(savedProfile)
        applyProfileSettings(savedProfile)
    }

    private fun applyProfileSettings(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)

            // The DAX profile already contains mondrian's factory tuning. Only
            // replay parameters that the user has explicitly overridden; using
            // synthetic defaults here would silently replace the stock profile.
            if (prefs.contains(DolbyConstants.PREF_IEQ)) {
                val ieqPreset = prefs.getString(DolbyConstants.PREF_IEQ, null)
                    ?.toIntOrNull()
                if (ieqPreset != null) {
                    dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, ieqPreset, profile)
                }
            }

            if (prefs.contains(DolbyConstants.PREF_HP_VIRTUALIZER)) {
                dolbyEffect.setDapParameter(
                    DsParam.HEADPHONE_VIRTUALIZER,
                    prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false),
                    profile
                )
            }

            if (prefs.contains(DolbyConstants.PREF_SPK_VIRTUALIZER)) {
                dolbyEffect.setDapParameter(
                    DsParam.SPEAKER_VIRTUALIZER,
                    prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false),
                    profile
                )
            }

            if (stereoWideningSupported &&
                prefs.contains(DolbyConstants.PREF_STEREO_WIDENING)) {
                dolbyEffect.setDapParameter(
                    DsParam.STEREO_WIDENING_AMOUNT,
                    prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32),
                    profile
                )
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
                dolbyEffect.setDapParameter(
                    DsParam.DIALOGUE_ENHANCER_ENABLE,
                    prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false),
                    profile
                )
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
                dolbyEffect.setDapParameter(
                    DsParam.DIALOGUE_ENHANCER_AMOUNT,
                    prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6),
                    profile
                )
            }

            if (prefs.contains(DolbyConstants.PREF_BASS)) {
                dolbyEffect.setDapParameter(
                    DsParam.BASS_ENHANCER_ENABLE,
                    prefs.getBoolean(DolbyConstants.PREF_BASS, false),
                    profile
                )
            }

            if (volumeLevelerSupported && prefs.contains(DolbyConstants.PREF_VOLUME)) {
                dolbyEffect.setDapParameter(
                    DsParam.VOLUME_LEVELER_ENABLE,
                    prefs.getBoolean(DolbyConstants.PREF_VOLUME, false),
                    profile
                )
            }

            DolbyConstants.dlog(TAG, "Restored explicit user overrides for profile $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore profile settings: ${e.message}")
        }
    }
    fun applySavedState() {
        try {
            checkEffect()
            val enabled = defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
            dolbyEffect.dsOn = enabled
            if (enabled) {
                restoreSavedProfileIfNeeded()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to apply saved Dolby state: ${e.message}")
        }
    }

    private fun checkIsOnSpeaker(): Boolean {
        return try {
            val device = audioManager.getDevicesForAttributes(ATTRIBUTES_MEDIA)[0]
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking speaker state: ${e.message}")
            false
        }
    }

    fun updateSpeakerState() {
        if (!isReleased) {
            _isOnSpeaker.value = checkIsOnSpeaker()
        }
    }

    fun getDolbyEnabled(): Boolean {
        return defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
    }

    fun setDolbyEnabled(enabled: Boolean) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.dsOn = enabled
            defaultPrefs.edit().putBoolean(DolbyConstants.PREF_ENABLE, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
        }
    }

    fun getCurrentProfile(): Int {
        return readSavedProfile() ?: 0
    }

    fun setCurrentProfile(profile: Int) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.profile = profile
            defaultPrefs.edit().putString(DolbyConstants.PREF_PROFILE, profile.toString()).apply()
            if (!verifyProfileSaved(profile)) {
                DolbyConstants.dlog(TAG, "WARNING: Profile may not have been saved correctly!")
            }
            restoreProfilePreset(profile)
            _currentProfile.value = profile
            DolbyConstants.dlog(TAG, "Profile set to: $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting current profile: ${e.message}")
        }
    }

    private fun restoreProfilePreset(profile: Int) {
        try {
            val baseGains = getBaseEqualizerQ4(profile) ?: return
            writeComposedEqualizer(profile, baseGains)
            DolbyConstants.dlog(TAG, "Restored base preset and derived EQ for profile $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore preset for profile $profile: ${e.message}")
        }
    }

    fun verifyProfileSaved(profile: Int): Boolean {
        val prefs = defaultPrefs.getString(DolbyConstants.PREF_PROFILE, "0")?.toIntOrNull()
        val saved = prefs == profile
        DolbyConstants.dlog(TAG, "Profile verification: requested=$profile, saved=$prefs, match=$saved")
        return saved
    }

    private fun getProfilePrefs(profile: Int): SharedPreferences {
        return context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
    }

    fun hasProfileOverride(profile: Int, key: String): Boolean =
        getProfilePrefs(profile).contains(key)

    fun hasStoredBaseEqualizer(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.contains(DolbyConstants.PREF_EQ_BASE) ||
            prefs.contains(DolbyConstants.PREF_PRESET)
    }

    fun getBandMode(): BandMode {
        val mode = defaultPrefs.getString(DolbyConstants.PREF_BAND_MODE, "10")
        return when (mode) {
            "10" -> BandMode.TEN_BAND
            "15" -> BandMode.FIFTEEN_BAND
            "20" -> BandMode.TWENTY_BAND
            else -> BandMode.TEN_BAND
        }
    }

    fun setBandMode(mode: BandMode) {
        defaultPrefs.edit().putString(DolbyConstants.PREF_BAND_MODE, mode.value).apply()
    }

    fun getBassEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_BASS)) {
            return prefs.getBoolean(DolbyConstants.PREF_BASS, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting bass enhancer: ${e.message}")
            false
        }
    }

    fun setBassEnhancerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_BASS, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass enhancer: ${e.message}")
        }
    }

    fun getBassLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
    }

    fun getBassCurve(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
    }

    fun setBassCurve(profile: Int, curve: Int) {
        if (isReleased) return
        require(curve in BASS_CURVES.indices) { "Unknown bass curve: $curve" }

        ensureBaseEqualizer(profile)
        val prefs = getProfilePrefs(profile)
        if (prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0) == curve) return
        prefs.edit().putInt(DolbyConstants.PREF_BASS_CURVE, curve).apply()

        try {
            recomposeEqualizer(profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass curve: ${e.message}")
            throw e
        }
    }

    fun setBassLevel(profile: Int, level: Int) {
        if (isReleased) return
        require(level in 0..100) { "Bass level must be between 0 and 100" }

        try {
            ensureBaseEqualizer(profile)
            val prefs = getProfilePrefs(profile)
            prefs.edit().putInt(DolbyConstants.PREF_BASS_LEVEL, level).apply()

            // The stock DAP bass enhancer is the feature master; the level/curve
            // is a deterministic GEQ overlay derived from the immutable base EQ.
            setBassEnhancerEnabled(profile, level > 0)
            recomposeEqualizer(profile)
            DolbyConstants.dlog(TAG, "setBassLevel: profile=$profile level=$level")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass level: ${e.message}")
            throw e
        }
    }

    fun getTrebleEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.getBoolean(DolbyConstants.PREF_TREBLE, false)
    }

    fun setTrebleEnhancerEnabled(profile: Int, enabled: Boolean) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_TREBLE, enabled).apply()
    }

    fun getTrebleLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0)
    }

    fun setTrebleLevel(profile: Int, level: Int) {
        if (isReleased) return
        require(level in 0..100) { "Treble level must be between 0 and 100" }

        try {
            ensureBaseEqualizer(profile)
            val prefs = getProfilePrefs(profile)
            prefs.edit()
                .putInt(DolbyConstants.PREF_TREBLE_LEVEL, level)
                .putBoolean(DolbyConstants.PREF_TREBLE, level > 0)
                .apply()
            recomposeEqualizer(profile)
            DolbyConstants.dlog(TAG, "setTrebleLevel: profile=$profile level=$level")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting treble level: ${e.message}")
            throw e
        }
    }

    fun getVolumeLevelerEnabled(profile: Int): Boolean {
        if (!volumeLevelerSupported) return false
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_VOLUME)) {
            return prefs.getBoolean(DolbyConstants.PREF_VOLUME, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting volume leveler: ${e.message}")
            false
        }
    }

    fun setVolumeLevelerEnabled(profile: Int, enabled: Boolean) {
        if (!volumeLevelerSupported || isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_VOLUME, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
        }
    }

    fun getIeqPreset(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_IEQ)) {
            return prefs.getString(DolbyConstants.PREF_IEQ, null)?.toIntOrNull() ?: 0
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting IEQ preset: ${e.message}")
            0
        }
    }

    fun setIeqPreset(profile: Int, preset: Int) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, preset, profile)
            getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_IEQ, preset.toString()).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting IEQ preset: ${e.message}")
        }
    }

    fun getHeadphoneVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_HP_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting headphone virtualizer: ${e.message}")
            false
        }
    }

    fun setHeadphoneVirtualizerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting headphone virtualizer: ${e.message}")
        }
    }

    fun getSpeakerVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_SPK_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting speaker virtualizer: ${e.message}")
            false
        }
    }

    fun setSpeakerVirtualizerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
        }
    }

    fun getStereoWideningAmount(profile: Int): Int {
        if (!stereoWideningSupported) return 0
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_STEREO_WIDENING)) {
            return prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32)
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting stereo widening: ${e.message}")
            32
        }
    }

    fun setStereoWideningAmount(profile: Int, amount: Int) {
        if (!stereoWideningSupported || isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, amount, profile)
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_STEREO_WIDENING, amount).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
        }
    }

    fun getDialogueEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
            return prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer: ${e.message}")
            false
        }
    }

    fun setDialogueEnhancerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, enabled, profile)
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_DIALOGUE, enabled).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
        }
    }

    fun getDialogueEnhancerAmount(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
            return prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6)
        }
        return try {
            dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer amount: ${e.message}")
            6
        }
    }

    fun setDialogueEnhancerAmount(profile: Int, amount: Int) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, amount, profile)
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, amount).apply()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
        }
    }

    fun getEqualizerGains(profile: Int, bandMode: BandMode): List<BandGain> {
        return try {
            val base = getBaseEqualizerQ4(profile)
                ?: dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            deserializeGains(base, bandMode)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting equalizer gains: ${e.message}")
            frequenciesForMode(bandMode).map { BandGain(frequency = it, gain = 0) }
        }
    }

    fun setEqualizerGains(profile: Int, bandGains: List<BandGain>, bandMode: BandMode) {
        if (isReleased) return

        try {
            checkEffect()
            val base = serializeGains(bandGains, bandMode)
            persistBaseEqualizer(profile, base)
            writeComposedEqualizer(profile, base)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting equalizer gains: ${e.message}")
        }
    }

    fun getPresetName(profile: Int): String {
        return try {
            val base = getBaseEqualizerQ4(profile)
                ?: dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
            val baseString = base.joinToString(",")

            val presetValues = context.resources.getStringArray(R.array.dolby_preset_values)
            val presetNames = context.resources.getStringArray(R.array.dolby_preset_entries)
            presetValues.forEachIndexed { index, preset ->
                if (gainsMatch(normalizePresetTo20Bands(preset), baseString)) {
                    return presetNames[index]
                }
            }

            presetsPrefs.all.forEach { (name, value) ->
                val encoded = value as? String ?: return@forEach
                val gainsPart = encoded.substringBefore("|")
                if (gainsMatch(normalizePresetTo20Bands(gainsPart), baseString)) {
                    return name
                }
            }

            context.getString(R.string.dolby_preset_custom)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting preset name: ${e.message}")
            context.getString(R.string.dolby_preset_custom)
        }
    }

    private fun normalizePresetTo20Bands(gainsString: String): String {
        val gains = gainsString.split(",").map { it.trim().toIntOrNull() ?: 0 }
        return when (gains.size) {
            20 -> gains.map(DapEqualizerMath::clampBoost).joinToString(",")
            15 -> serializeGains(
                BAND_FREQUENCIES_15.mapIndexed { index, frequency ->
                    BandGain(frequency, gains[index])
                },
                BandMode.FIFTEEN_BAND,
            ).joinToString(",")
            10 -> serializeGains(
                BAND_FREQUENCIES_10.mapIndexed { index, frequency ->
                    BandGain(frequency, gains[index])
                },
                BandMode.TEN_BAND,
            ).joinToString(",")
            else -> gainsString
        }
    }

    private fun gainsMatch(gains1: String, gains2: String): Boolean {
        val g1 = gains1.split(",").map { it.trim().toIntOrNull() ?: 0 }
        val g2 = gains2.split(",").map { it.trim().toIntOrNull() ?: 0 }
        if (g1.size != g2.size) return false
        return g1.zip(g2).all { (a, b) -> kotlin.math.abs(a - b) <= 1 }
    }

    fun getUserPresets(): List<EqualizerPreset> {
        synchronized(presetCacheLock) {
            cachedPresets?.let { return it }
            
            val bandMode = getBandMode()
            val presets = presetsPrefs.all.mapNotNull { (name, value) ->
                try {
                    val valueStr = value as? String ?: return@mapNotNull null
                    parsePreset(name, valueStr)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error parsing preset $name: ${e.message}")
                    null
                }
            }
            
            cachedPresets = presets
            return presets
        }
    }

    private fun parsePreset(name: String, valueStr: String): EqualizerPreset? {
        return try {
            if (valueStr.contains("|")) {
                val parts = valueStr.split("|")
                val presetBandMode = BandMode.fromValue(parts[1])
                val gains = parts[0].split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, presetBandMode),
                    isUserDefined = true,
                    bandMode = presetBandMode
                )
            } else {
                val gains = valueStr.split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, BandMode.TEN_BAND),
                    isUserDefined = true,
                    bandMode = BandMode.TEN_BAND
                )
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error parsing preset value: ${e.message}")
            null
        }
    }

    fun addUserPreset(name: String, bandGains: List<BandGain>, bandMode: BandMode) {
        try {
            val gains = serializeGains(bandGains, bandMode).joinToString(",")
            val value = "$gains|${bandMode.value}"
            presetsPrefs.edit().putString(name, value).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error adding user preset: ${e.message}")
        }
    }

    fun deleteUserPreset(name: String) {
        try {
            presetsPrefs.edit().remove(name).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error deleting user preset: ${e.message}")
        }
    }

    fun resetProfile(profile: Int) {
        if (isReleased) return
        
        try {
            checkEffect()
            dolbyEffect.resetProfileSpecificSettings(profile)
            context.deleteSharedPreferences("profile_$profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting profile: ${e.message}")
        }
    }

    fun resetAllProfiles() {
        if (isReleased) return
        
        try {
            checkEffect()
            context.resources.getStringArray(R.array.dolby_profile_values)
                .map { it.toInt() }
                .forEach { resetProfile(it) }
            setCurrentProfile(0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting all profiles: ${e.message}")
        }
    }

    private fun frequenciesForMode(mode: BandMode): List<Int> = when (mode) {
        BandMode.TEN_BAND -> BAND_FREQUENCIES_10
        BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
        BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
    }

    private fun deserializeGains(gains: IntArray, bandMode: BandMode): List<BandGain> {
        val frequencies = when (bandMode) {
            BandMode.TEN_BAND -> BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
        }
        
        val indices = when (bandMode) {
            BandMode.TEN_BAND -> TEN_BAND_INDICES
            BandMode.FIFTEEN_BAND -> FIFTEEN_BAND_INDICES
            BandMode.TWENTY_BAND -> (0..19).toList()
        }
        
        return frequencies.mapIndexed { index, freq ->
            val gainIndex = indices.getOrNull(index) ?: index
            BandGain(frequency = freq, gain = gains.getOrElse(gainIndex) { 0 })
        }
    }

    private fun serializeGains(
        bandGains: List<BandGain>,
        bandMode: BandMode,
    ): IntArray {
        val sourceFrequencies = when (bandMode) {
            BandMode.TEN_BAND -> BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
        }
        val sourceGains = sourceFrequencies.mapIndexed { index, _ ->
            DapEqualizerMath.clampBoost(bandGains.getOrNull(index)?.gain ?: 0)
        }

        return IntArray(BAND_FREQUENCIES_20.size) { index ->
            DapEqualizerMath.interpolateQ4(
                BAND_FREQUENCIES_20[index],
                sourceFrequencies,
                sourceGains,
            )
        }
    }

    fun getMidEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.getBoolean(DolbyConstants.PREF_MID, false)
    }

    fun setMidEnhancerEnabled(profile: Int, enabled: Boolean) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_MID, enabled).apply()
    }

    fun getMidLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0)
    }

    fun setMidLevel(profile: Int, level: Int) {
        if (isReleased) return
        require(level in 0..100) { "Mid level must be between 0 and 100" }

        try {
            ensureBaseEqualizer(profile)
            val prefs = getProfilePrefs(profile)
            prefs.edit()
                .putInt(DolbyConstants.PREF_MID_LEVEL, level)
                .putBoolean(DolbyConstants.PREF_MID, level > 0)
                .apply()
            recomposeEqualizer(profile)
            DolbyConstants.dlog(TAG, "setMidLevel: profile=$profile level=$level")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting mid level: ${e.message}")
            throw e
        }
    }

    private fun ensureBaseEqualizer(profile: Int): IntArray {
        getBaseEqualizerQ4(profile)?.let { return it }

        checkEffect()
        val current = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
        persistBaseEqualizer(profile, current)
        return current
    }

    private fun recomposeEqualizer(profile: Int) {
        checkEffect()
        val base = getBaseEqualizerQ4(profile)
            ?: dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).also {
                persistBaseEqualizer(profile, it)
            }
        writeComposedEqualizer(profile, base)
    }

    private fun writeComposedEqualizer(profile: Int, baseGains: IntArray) {
        val effective = composeEqualizer(profile, baseGains)
        dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, effective, profile)
    }

    private fun composeEqualizer(profile: Int, baseGains: IntArray): IntArray {
        val prefs = getProfilePrefs(profile)
        val result = IntArray(BAND_FREQUENCIES_20.size) { index ->
            DapEqualizerMath.clampBoost(baseGains.getOrElse(index) { 0 })
        }

        val bassLevel = prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0).coerceIn(0, 100)
        if (bassLevel > 0) {
            val curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
                .coerceIn(BASS_CURVES.indices)
            val weights = BASS_CURVES[curve]
            val baseGain = bassLevel * BASS_GAIN_MULTIPLIER
            weights.forEachIndexed { index, weight ->
                if (index < result.size) {
                    result[index] += (baseGain * weight).toInt()
                }
            }
        }

        val midLevel = prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0).coerceIn(0, 100)
        if (midLevel > 0) {
            val gain = (midLevel * MID_GAIN_MULTIPLIER).toInt()
            for (index in 5..13) result[index] += gain
        }

        val trebleLevel = prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0).coerceIn(0, 100)
        if (trebleLevel > 0) {
            val gain = (trebleLevel * TREBLE_GAIN_MULTIPLIER).toInt()
            for (index in 14..19) result[index] += gain
        }

        for (index in result.indices) {
            result[index] = DapEqualizerMath.clampBoost(result[index])
        }
        return result
    }

    private fun getBaseEqualizerQ4(profile: Int): IntArray? {
        val prefs = getProfilePrefs(profile)
        migrateLegacyEqualizerIfNeeded(profile, prefs)
        val encoded = prefs.getString(DolbyConstants.PREF_EQ_BASE, null)
            ?: prefs.getString(DolbyConstants.PREF_PRESET, null)
            ?: return null
        return parseTwentyBandGains(encoded)
    }

    private fun persistBaseEqualizer(profile: Int, baseGains: IntArray) {
        val normalized = IntArray(BAND_FREQUENCIES_20.size) { index ->
            DapEqualizerMath.clampBoost(baseGains.getOrElse(index) { 0 })
        }
        val encoded = normalized.joinToString(",")
        getProfilePrefs(profile).edit()
            .putString(DolbyConstants.PREF_EQ_BASE, encoded)
            // Keep PREF_PRESET as a compatibility alias, but it now stores the
            // immutable base curve instead of the composed/effective curve.
            .putString(DolbyConstants.PREF_PRESET, encoded)
            .putInt(DolbyConstants.PREF_EQ_COMPOSITION_VERSION, EQ_COMPOSITION_VERSION)
            .apply()
    }

    private fun parseTwentyBandGains(encoded: String): IntArray? {
        val values = encoded.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (values.size != BAND_FREQUENCIES_20.size) return null
        return IntArray(values.size) { index ->
            DapEqualizerMath.clampBoost(values[index])
        }
    }

    /**
     * Old builds saved the already-composed EQ in PREF_PRESET. Recover an
     * estimated immutable base once, then all future changes are recomputed
     * from that base. This prevents cumulative drift after clipping.
     */
    private fun migrateLegacyEqualizerIfNeeded(
        profile: Int,
        prefs: SharedPreferences,
    ) {
        if (prefs.getInt(DolbyConstants.PREF_EQ_COMPOSITION_VERSION, 0) >=
            EQ_COMPOSITION_VERSION) return

        val legacy = prefs.getString(DolbyConstants.PREF_PRESET, null)
            ?.let(::parseTwentyBandGains)
        if (legacy == null) {
            prefs.edit()
                .putInt(DolbyConstants.PREF_EQ_COMPOSITION_VERSION, EQ_COMPOSITION_VERSION)
                .apply()
            return
        }

        val estimatedBase = legacy.copyOf()
        val bassLevel = prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0).coerceIn(0, 100)
        if (bassLevel > 0) {
            val curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
                .coerceIn(BASS_CURVES.indices)
            val baseGain = bassLevel * BASS_GAIN_MULTIPLIER
            BASS_CURVES[curve].forEachIndexed { index, weight ->
                if (index < estimatedBase.size) {
                    estimatedBase[index] -= (baseGain * weight).toInt()
                }
            }
        }

        val midLevel = prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0).coerceIn(0, 100)
        if (midLevel > 0) {
            val gain = (midLevel * MID_GAIN_MULTIPLIER).toInt()
            for (index in 5..13) estimatedBase[index] -= gain
        }

        val trebleLevel = prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0).coerceIn(0, 100)
        if (trebleLevel > 0) {
            val gain = (trebleLevel * TREBLE_GAIN_MULTIPLIER).toInt()
            for (index in 14..19) estimatedBase[index] -= gain
        }

        persistBaseEqualizer(profile, estimatedBase)
        DolbyConstants.dlog(
            TAG,
            "Migrated legacy composed EQ to immutable base for profile=$profile"
        )
    }

    override fun close() {
        // Process-wide singleton: transient UI/service owners must never release
        // the global session-0 DAP controller. Process teardown releases it.
    }

    companion object {
        private const val TAG = "DolbyRepository"
        private const val EFFECT_PRIORITY = 100
        private const val EQ_COMPOSITION_VERSION = 1

        @Volatile
        private var instance: DolbyRepository? = null

        fun getInstance(context: Context): DolbyRepository {
            return instance ?: synchronized(this) {
                instance ?: DolbyRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
        
        private const val BASS_GAIN_MULTIPLIER = 1.4f
        private const val MID_GAIN_MULTIPLIER = 1.3f
        private const val TREBLE_GAIN_MULTIPLIER = 1.5f
        
        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        // DAX GEQ uses the exact 20-band frequency order from the mondrian
        // factory tuning. Reduced modes expose labels for the DAP indices they
        // actually control instead of synthetic frequencies.
        val BAND_FREQUENCIES_20 = listOf(
            47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
            2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
        )

        private val TEN_BAND_INDICES = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        val BAND_FREQUENCIES_10 = TEN_BAND_INDICES.map(BAND_FREQUENCIES_20::get)

        private val FIFTEEN_BAND_INDICES =
            listOf(0, 1, 2, 3, 4, 5, 6, 8, 11, 12, 14, 15, 17, 18, 19)
        val BAND_FREQUENCIES_15 = FIFTEEN_BAND_INDICES.map(BAND_FREQUENCIES_20::get)
        private val BASS_CURVES = listOf(
            floatArrayOf(
                1.00f, 1.00f, 0.95f, 0.90f, 0.80f, 0.70f, 0.55f, 0.40f, 0.25f, 0.15f,
                0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            ),
            floatArrayOf(
                1.20f, 1.15f, 1.05f, 0.90f, 0.70f, 0.55f, 0.40f, 0.25f, 0.10f, 0.05f,
                0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            ),
            floatArrayOf(
                0.90f, 0.95f, 1.00f, 1.00f, 0.90f, 0.75f, 0.60f, 0.45f, 0.30f, 0.20f,
                0.10f, 0.05f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f
            )
        )
    }
}
