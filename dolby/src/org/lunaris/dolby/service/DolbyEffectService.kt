/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: DolbyRepository
    private lateinit var deviceStateManager: DeviceStateManager

    private var activeDeviceKey: String? = null
    private var activeRouteId: String? = null
    private var playbackActive = false
    private var routeRetryCount = 0

    private val routeRefreshRunnable = Runnable { handleDeviceChange() }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            scheduleRouteRefresh("devices added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            // Snapshot exactly once when the resolved media route actually
            // changes. Saving every removed AudioDeviceInfo can write the new
            // route's state into the old device slot during asynchronous routing.
            scheduleRouteRefresh("devices removed")
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true

            // AudioPlaybackCallback is delivered for many configuration changes,
            // not only focus recovery. Replaying the complete DAP state on every
            // callback causes redundant parameter traffic while audio is running.
            // Restore only on the inactive -> active edge, which keeps the original
            // focus-loss recovery behavior without repeatedly rewriting Dolby.
            if (isActive && !playbackActive) {
                Log.d(TAG, "Playback became active, validating saved Dolby state")
                repository.applySavedState()
            }
            playbackActive = isActive
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = DolbyRepository.getInstance(this)
        deviceStateManager = DeviceStateManager(this)

        val currentDevice = getCurrentOutputDevice()
        if (currentDevice != null) {
            activeDeviceKey = deviceStateManager.deviceKey(currentDevice)
            activeRouteId = currentDevice.routeId(activeDeviceKey!!)

            if (isDeviceStateMemoryEnabled) {
                val restored = deviceStateManager.restoreSnapshot(activeDeviceKey!!, repository)
                if (!restored) {
                    Log.d(TAG, "No usable snapshot for initial route; applying saved state")
                    repository.applySavedState()
                }
            } else {
                repository.applySavedState()
            }
        } else {
            repository.applySavedState()
        }

        repository.updateSpeakerState()
        playbackActive = try {
            audioManager.activePlaybackConfigurations.any { it.isActive }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query initial playback state", e)
            false
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        Log.d(
            TAG,
            "Dolby effect service created: route=$activeRouteId deviceKey=$activeDeviceKey"
        )
    }

    private fun scheduleRouteRefresh(reason: String) {
        routeRetryCount = 0
        handler.removeCallbacks(routeRefreshRunnable)
        handler.postDelayed(routeRefreshRunnable, ROUTE_SETTLE_DELAY_MS)
        Log.d(TAG, "Scheduled media-route refresh: $reason")
    }

    private fun handleDeviceChange() {
        val newDevice = getCurrentOutputDevice()
        if (newDevice == null) {
            if (routeRetryCount < MAX_ROUTE_RETRIES) {
                routeRetryCount++
                Log.d(TAG, "Media route unresolved; retry $routeRetryCount/$MAX_ROUTE_RETRIES")
                handler.postDelayed(routeRefreshRunnable, ROUTE_RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "Media route remained unresolved; preserving previous Dolby state")
            }
            return
        }

        routeRetryCount = 0
        val newKey = deviceStateManager.deviceKey(newDevice)
        val newRouteId = newDevice.routeId(newKey)

        if (newRouteId == activeRouteId) {
            repository.updateSpeakerState()
            Log.d(TAG, "Media route unchanged: $newRouteId")
            return
        }

        val oldKey = activeDeviceKey
        if (isDeviceStateMemoryEnabled && oldKey != null) {
            Log.d(TAG, "Saving snapshot for previous route: $oldKey")
            deviceStateManager.saveSnapshot(oldKey, repository)
        }

        if (isDeviceStateMemoryEnabled) {
            Log.d(TAG, "Restoring snapshot for new route: $newKey")
            val restored = deviceStateManager.restoreSnapshot(newKey, repository)
            if (!restored) {
                // Preserve upstream behavior: a first-time device inherits the
                // current saved state, then receives its own snapshot later.
                Log.d(TAG, "First-time/unavailable snapshot; applying saved state as base")
                repository.applySavedState()
            }
        } else {
            Log.d(TAG, "Device state memory disabled; applying saved state")
            repository.applySavedState()
        }

        repository.updateSpeakerState()
        activeDeviceKey = newKey
        activeRouteId = newRouteId
        Log.d(TAG, "Media route changed: route=$newRouteId deviceKey=$newKey")
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val routedDevice = try {
            audioManager
                .getDevicesForAttributes(ATTRIBUTES_MEDIA)
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active media route", e)
            null
        } ?: return null

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val routedAddress = routedDevice.address.orEmpty()

        return outputs.firstOrNull { device ->
            device.isSink &&
                device.type == routedDevice.type &&
                (routedAddress.isEmpty() || device.address == routedAddress)
        } ?: outputs.firstOrNull { device ->
            device.isSink && device.type == routedDevice.type
        }.also { device ->
            if (device == null) {
                Log.w(
                    TAG,
                    "Unable to map active media route: " +
                        "type=${routedDevice.type}, address=${routedDevice.address}"
                )
            }
        }
    }

    private fun AudioDeviceInfo.routeId(deviceKey: String): String = "$type:$deviceKey"

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate() performs the initial restore. Repeated startService() calls
        // while the service is already alive must not rewrite the complete DAP
        // state a second time.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)

        if (isDeviceStateMemoryEnabled) {
            activeDeviceKey?.let { key ->
                Log.d(TAG, "Saving snapshot on service teardown: $key")
                deviceStateManager.saveSnapshot(key, repository)
            }
        }

        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        super.onDestroy()
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"
        private const val ROUTE_SETTLE_DELAY_MS = 250L
        private const val ROUTE_RETRY_DELAY_MS = 250L
        private const val MAX_ROUTE_RETRIES = 4

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
