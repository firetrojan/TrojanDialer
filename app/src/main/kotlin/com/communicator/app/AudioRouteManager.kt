package com.communicator.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mutableStateFlow

class AudioRouteManager(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _currentRoute = mutableStateFlow<AudioRoute>(AudioRoute.EARPIECE)
    val currentRoute: StateFlow<AudioRoute> = _currentRoute

    private val _availableRoutes = mutableStateFlow<List<AudioRoute>>(listOf(AudioRoute.EARPIECE))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes

    private var currentCall: Call? = null

    init {
        refreshAvailableRoutes()
    }

    fun setCurrentCall(call: Call?) {
        currentCall = call
    }

    fun clearCurrentCall() {
        currentCall = null
    }

    // Query current audio route from Telecom/Call if available
    fun refreshFromTelecom(call: Call?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call?.let { c ->
                val route = c.details.audioRoute
                _currentRoute.value = mapTelecomAudioRoute(route)
            }
        } else {
            refreshFromLegacy()
        }
    }

    private fun mapTelecomAudioRoute(route: Int): AudioRoute {
        return when (route) {
            android.telecom.Call.AUDIO_ROUTE_EARPIECE -> AudioRoute.EARPIECE
            android.telecom.Call.AUDIO_ROUTE_SPEAKER -> AudioRoute.SPEAKER
            android.telecom.Call.AUDIO_ROUTE_BLUETOOTH -> AudioRoute.BLUETOOTH
            android.telecom.Call.AUDIO_ROUTE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
            android.telecom.Call.AUDIO_ROUTE_WIRED_HEADPHONES -> AudioRoute.WIRED_HEADPHONES
            else -> AudioRoute.EARPIECE
        }
    }

    fun refreshAvailableRoutes() {
        val routes = mutableListOf<AudioRoute>()
        routes.add(AudioRoute.EARPIECE)
        routes.add(AudioRoute.SPEAKER)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.forEach { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> routes.add(AudioRoute.WIRED_HEADSET)
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> routes.add(AudioRoute.WIRED_HEADPHONES)
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_BLE -> routes.add(AudioRoute.BLUETOOTH)
                    else -> {}
                }
            }
        } else {
            if (audioManager.isWiredHeadsetOn) {
                routes.add(AudioRoute.WIRED_HEADSET)
            }
            if (audioManager.isBluetoothScoOn) {
                routes.add(AudioRoute.BLUETOOTH)
            }
        }

        _availableRoutes.value = routes.distinct()
    }

    private fun refreshFromLegacy() {
        if (audioManager.isSpeakerphoneOn) {
            _currentRoute.value = AudioRoute.SPEAKER
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Check for wired/Bluetooth via AudioManager
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var hasWired = false
            var hasBluetooth = false
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> hasWired = true
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_BLE -> hasBluetooth = true
                }
            }
            if (hasWired) {
                _currentRoute.value = AudioRoute.WIRED_HEADSET
            } else if (hasBluetooth) {
                _currentRoute.value = AudioRoute.BLUETOOTH
            } else {
                _currentRoute.value = AudioRoute.EARPIECE
            }
        } else {
            if (audioManager.isWiredHeadsetOn) {
                _currentRoute.value = AudioRoute.WIRED_HEADSET
            } else if (audioManager.isBluetoothScoOn) {
                _currentRoute.value = AudioRoute.BLUETOOTH
            } else {
                _currentRoute.value = AudioRoute.EARPIECE
            }
        }
    }

    fun setAudioRoute(route: AudioRoute): Boolean {
        return when (route) {
            AudioRoute.EARPIECE -> {
                audioManager.isSpeakerphoneOn = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }
                _currentRoute.value = AudioRoute.EARPIECE
                true
            }
            AudioRoute.SPEAKER -> {
                audioManager.isSpeakerphoneOn = true
                _currentRoute.value = AudioRoute.SPEAKER
                true
            }
            AudioRoute.WIRED_HEADSET -> {
                // Wired headset is automatic when plugged in
                _currentRoute.value = AudioRoute.WIRED_HEADSET
                true
            }
            AudioRoute.BLUETOOTH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Modern Bluetooth routing
                    audioManager.isBluetoothScoOn = true
                    audioManager.startBluetoothSco()
                } else {
                    audioManager.isBluetoothScoOn = true
                    audioManager.startBluetoothSco()
                }
                _currentRoute.value = AudioRoute.BLUETOOTH
                true
            }
            AudioRoute.WIRED_HEADPHONES -> {
                _currentRoute.value = AudioRoute.WIRED_HEADPHONES
                true
            }
            else -> false
        }
    }

    fun getCurrentRoute(): AudioRoute = _currentRoute.value
    fun getAvailableRoutes(): List<AudioRoute> = _availableRoutes.value

    enum class AudioRoute {
        EARPIECE,
        SPEAKER,
        WIRED_HEADSET,
        WIRED_HEADPHONES,
        BLUETOOTH
    }
}
