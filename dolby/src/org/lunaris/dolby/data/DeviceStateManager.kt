/*
 * Copyright (C) 2026 tranQuila-Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo

import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.domain.models.BandGain
import org.lunaris.dolby.domain.models.BandMode

class DeviceStateManager(private val context: Context) {

    fun deviceKey(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                val addr = device.address?.takeIf { it.isNotBlank() } ?: "unknown"
                "bt_${addr.replace(":", "_")}"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> "wired_headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            else -> "device_type_${device.type}"
        }
    }

    fun deviceDisplayName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> productName ?: "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> productName ?: "USB Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> productName ?: "Bluetooth Device"
            else -> productName ?: "Audio Device"
        }
    }

    fun saveSnapshot(deviceKey: String, repository: DolbyRepository) {
        val profile = repository.getCurrentProfile()
        val prefs = getDevicePrefs(deviceKey)
        val editor = prefs.edit().clear()

        editor.putInt(KEY_VERSION, SNAPSHOT_VERSION)
        editor.putInt(KEY_PROFILE, profile)

        // Direct DAP_offload can be setter-only. Snapshot only values that this
        // app explicitly owns instead of treating a zero-filled readback as a
        // real stock Dolby value and later overwriting factory DAX defaults.
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_IEQ)) {
            editor.putInt(KEY_IEQ, repository.getIeqPreset(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_HP_VIRTUALIZER)) {
            editor.putBoolean(KEY_HP_VIRT, repository.getHeadphoneVirtualizerEnabled(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_SPK_VIRTUALIZER)) {
            editor.putBoolean(KEY_SPK_VIRT, repository.getSpeakerVirtualizerEnabled(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_DIALOGUE)) {
            editor.putBoolean(KEY_DIALOGUE, repository.getDialogueEnhancerEnabled(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
            editor.putInt(KEY_DIALOGUE_AMT, repository.getDialogueEnhancerAmount(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_BASS)) {
            editor.putBoolean(KEY_BASS_ENABLED, repository.getBassEnhancerEnabled(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_BASS_LEVEL)) {
            editor.putInt(KEY_BASS_LEVEL, repository.getBassLevel(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_BASS_CURVE)) {
            editor.putInt(KEY_BASS_CURVE, repository.getBassCurve(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_TREBLE_LEVEL)) {
            editor.putInt(KEY_TREBLE_LEVEL, repository.getTrebleLevel(profile))
        }
        if (repository.hasProfileOverride(profile, DolbyConstants.PREF_MID_LEVEL)) {
            editor.putInt(KEY_MID_LEVEL, repository.getMidLevel(profile))
        }
        if (repository.volumeLevelerSupported &&
            repository.hasProfileOverride(profile, DolbyConstants.PREF_VOLUME)) {
            editor.putBoolean(KEY_VOLUME, repository.getVolumeLevelerEnabled(profile))
        }
        if (repository.stereoWideningSupported &&
            repository.hasProfileOverride(profile, DolbyConstants.PREF_STEREO_WIDENING)) {
            editor.putInt(KEY_STEREO, repository.getStereoWideningAmount(profile))
        }

        var bandCount = 0
        if (repository.hasStoredBaseEqualizer(profile)) {
            val gains = repository.getEqualizerGains(profile, BandMode.TWENTY_BAND)
            bandCount = gains.size
            editor.putInt(KEY_EQ_BAND_COUNT, gains.size)
            editor.putString(KEY_EQ_GAINS, gains.joinToString(",") { it.gain.toString() })
        }

        editor.apply()
        DolbyConstants.dlog(
            TAG,
            "Snapshot saved for device=$deviceKey profile=$profile bands=$bandCount v=$SNAPSHOT_VERSION"
        )
    }

    fun restoreSnapshot(deviceKey: String, repository: DolbyRepository): Boolean {
        val prefs = getDevicePrefs(deviceKey)

        if (!prefs.contains(KEY_VERSION)) {
            DolbyConstants.dlog(TAG, "No snapshot for device=$deviceKey")
            return false
        }

        val storedVersion = prefs.getInt(KEY_VERSION, -1)
        if (storedVersion != SNAPSHOT_VERSION) {
            DolbyConstants.dlog(TAG,
                "Snapshot version mismatch for $deviceKey: stored=$storedVersion current=$SNAPSHOT_VERSION — discarding")
            clearSnapshot(deviceKey)
            return false
        }

        return try {
            val profile = prefs.getInt(KEY_PROFILE, 0)

            // Dolby power is global, not per-output-device. Restoring a device
            // snapshot must not toggle the session-0 effect or fight its owner.
            repository.setCurrentProfile(profile)

            val storedBandCount = prefs.getInt(KEY_EQ_BAND_COUNT, -1)
            val gainsStr = prefs.getString(KEY_EQ_GAINS, null)
            if (gainsStr != null && storedBandCount > 0) {
                val gains = gainsStr.split(",").mapNotNull { it.toIntOrNull() }
                if (gains.size == storedBandCount) {
                    val bandGains = gains.mapIndexed { i, g ->
                        BandGain(
                            frequency = DolbyRepository.BAND_FREQUENCIES_20.getOrElse(i) { i },
                            gain = g
                        )
                    }
                    repository.setEqualizerGains(profile, bandGains, BandMode.TWENTY_BAND)
                } else {
                    DolbyConstants.dlog(TAG,
                        "EQ band count mismatch for $deviceKey: stored=$storedBandCount actual=${gains.size} — skipping EQ restore")
                }
            }

            if (prefs.contains(KEY_IEQ)) {
                repository.setIeqPreset(profile, prefs.getInt(KEY_IEQ, 0))
            }

            if (prefs.contains(KEY_HP_VIRT)) {
                repository.setHeadphoneVirtualizerEnabled(profile, prefs.getBoolean(KEY_HP_VIRT, false))
            }
            if (prefs.contains(KEY_SPK_VIRT)) {
                repository.setSpeakerVirtualizerEnabled(profile, prefs.getBoolean(KEY_SPK_VIRT, false))
            }

            if (prefs.contains(KEY_DIALOGUE)) {
                repository.setDialogueEnhancerEnabled(profile, prefs.getBoolean(KEY_DIALOGUE, false))
            }
            if (prefs.contains(KEY_DIALOGUE_AMT)) {
                repository.setDialogueEnhancerAmount(profile, prefs.getInt(KEY_DIALOGUE_AMT, 6))
            }

            if (prefs.contains(KEY_BASS_ENABLED)) {
                repository.setBassEnhancerEnabled(profile, prefs.getBoolean(KEY_BASS_ENABLED, false))
            }
            if (prefs.contains(KEY_BASS_CURVE)) {
                repository.setBassCurve(profile, prefs.getInt(KEY_BASS_CURVE, 0))
            }
            if (prefs.contains(KEY_BASS_LEVEL)) {
                repository.setBassLevel(profile, prefs.getInt(KEY_BASS_LEVEL, 0))
            }
            if (prefs.contains(KEY_TREBLE_LEVEL)) {
                repository.setTrebleLevel(profile, prefs.getInt(KEY_TREBLE_LEVEL, 0))
            }
            if (prefs.contains(KEY_MID_LEVEL)) {
                repository.setMidLevel(profile, prefs.getInt(KEY_MID_LEVEL, 0))
            }

            if (repository.volumeLevelerSupported && prefs.contains(KEY_VOLUME)) {
                repository.setVolumeLevelerEnabled(profile, prefs.getBoolean(KEY_VOLUME, false))
            }
            if (repository.stereoWideningSupported && prefs.contains(KEY_STEREO)) {
                repository.setStereoWideningAmount(profile, prefs.getInt(KEY_STEREO, 32))
            }

            DolbyConstants.dlog(TAG,
                "Snapshot restored for device=$deviceKey profile=$profile v=$storedVersion")
            true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG,
                "Failed to restore snapshot for $deviceKey: ${e.message} — discarding")
            clearSnapshot(deviceKey)
            false
        }
    }

    fun hasSnapshot(deviceKey: String): Boolean {
        val prefs = getDevicePrefs(deviceKey)
        return prefs.contains(KEY_VERSION) &&
                prefs.getInt(KEY_VERSION, -1) == SNAPSHOT_VERSION
    }

    fun clearSnapshot(deviceKey: String) {
        getDevicePrefs(deviceKey).edit().clear().apply()
        DolbyConstants.dlog(TAG, "Snapshot cleared for device=$deviceKey")
    }

    fun getAllDeviceKeys(): List<String> {
        val dir = context.filesDir.parentFile?.let {
            java.io.File(it, "shared_prefs")
        } ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("device_state_") }
            ?.map { it.name.removePrefix("device_state_").removeSuffix(".xml") }
            ?: emptyList()
    }

    private fun getDevicePrefs(deviceKey: String): SharedPreferences =
        context.getSharedPreferences("device_state_$deviceKey", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DeviceStateManager"

        const val SNAPSHOT_VERSION = 2

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_PROFILE = "profile"
        private const val KEY_IEQ = "ieq"
        private const val KEY_HP_VIRT = "hp_virt"
        private const val KEY_SPK_VIRT = "spk_virt"
        private const val KEY_DIALOGUE = "dialogue"
        private const val KEY_DIALOGUE_AMT  = "dialogue_amt"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_LEVEL = "bass_level"
        private const val KEY_BASS_CURVE = "bass_curve"
        private const val KEY_TREBLE_LEVEL = "treble_level"
        private const val KEY_MID_LEVEL = "mid_level"
        private const val KEY_VOLUME = "volume"
        private const val KEY_STEREO = "stereo"
        private const val KEY_EQ_BAND_COUNT = "eq_band_count"
        private const val KEY_EQ_GAINS = "eq_gains"
    }
}
