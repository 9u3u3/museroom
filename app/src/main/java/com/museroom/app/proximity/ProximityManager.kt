package com.museroom.app.proximity

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.museroom.app.net.NearbyListener
import com.museroom.app.net.ProximityApi
import com.museroom.app.privacy.PrivacyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bluetooth Low Energy presence.
 *
 * The phone advertises a rotating token and listens for others. Nothing about
 * the music travels over the radio; that already reaches the server, so the only
 * job here is noticing that somebody is in the room.
 *
 * BLE rather than Wi-Fi on purpose. Wi-Fi Aware is unevenly supported, Wi-Fi
 * Direct discovery is slow and intrusive, and grouping by network would put a
 * whole ISP in one room. BLE reaches roughly ten to thirty metres indoors, which
 * is the same café rather than the same building, and that is the intent.
 */
class ProximityManager private constructor(private val context: Context) {

    private val api = ProximityApi.get(context)
    private val privacy = PrivacyState.get(context)

    private val _nearby = MutableStateFlow<List<NearbyListener>>(emptyList())
    val nearby: StateFlow<List<NearbyListener>> = _nearby.asStateFlow()

    private val _state = MutableStateFlow<ProximityStatus>(ProximityStatus.Off)
    val state: StateFlow<ProximityStatus> = _state.asStateFlow()

    private val seen = mutableMapOf<String, Long>()
    private var scope: CoroutineScope? = null
    private var advertising = false

    private val adapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            _state.value = ProximityStatus.Failed("Could not broadcast (code $errorCode).")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val payload = result?.scanRecord?.getManufacturerSpecificData(Beacon.MANUFACTURER_ID)
            val token = Beacon.tokenFrom(payload) ?: return
            synchronized(seen) { seen[token] = SystemClock.elapsedRealtime() }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _state.value = ProximityStatus.Failed("Could not scan (code $errorCode).")
        }
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    /** Everything this needs, which differs sharply across Android versions. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Before Android 12 a BLE scan counted as a location capability, so
            // there is no way to scan without asking for location.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    @Synchronized
    fun start() {
        if (scope != null) return
        if (!hasPermissions()) {
            _state.value = ProximityStatus.NeedsPermission
            return
        }
        if (!bluetoothReady()) {
            _state.value = ProximityStatus.BluetoothOff
            return
        }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        _state.value = ProximityStatus.Searching

        newScope.launch { api.setEnabled(true) }
        newScope.launch { rotateForever() }
        newScope.launch { resolveForever() }
        startScanning()
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        stopAdvertising()
        stopScanning()
        synchronized(seen) { seen.clear() }
        _nearby.value = emptyList()
        _state.value = ProximityStatus.Off

        // Withdrawing matters: leaving a live beacon behind would keep answering
        // for someone who has turned the feature off.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            api.setEnabled(false)
            api.withdraw()
        }
    }

    private suspend fun rotateForever() {
        while (true) {
            // A private session means not discoverable either. Someone who has
            // stopped recording has plainly not agreed to being found.
            if (privacy.privateSession.value) {
                stopAdvertising()
                _state.value = ProximityStatus.PausedForPrivacy
            } else {
                val token = Beacon.newToken()
                api.publish(token, System.currentTimeMillis() + Beacon.LIFETIME_MS)
                    .onSuccess {
                        startAdvertising(token)
                        if (_state.value !is ProximityStatus.Failed) {
                            _state.value = ProximityStatus.Searching
                        }
                    }
                    .onFailure { _state.value = ProximityStatus.Failed(it.message ?: "Publish failed") }
            }
            delay(Beacon.ROTATE_AFTER_MS)
        }
    }

    private suspend fun resolveForever() {
        while (true) {
            delay(RESOLVE_EVERY_MS)
            val fresh = synchronized(seen) {
                val cutoff = SystemClock.elapsedRealtime() - FORGET_AFTER_MS
                seen.entries.removeAll { it.value < cutoff }
                seen.keys.toList()
            }
            api.resolve(fresh).onSuccess { _nearby.value = it }
        }
    }

    private fun startAdvertising(token: String) {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        stopAdvertising()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Beacon.MANUFACTURER_ID, Beacon.payload(token))
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onSuccess { advertising = true }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
    }

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                Beacon.MANUFACTURER_ID,
                Beacon.PREFIX,
                ByteArray(Beacon.PREFIX.size) { 0xFF.toByte() },
            )
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
    }

    private fun stopScanning() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    companion object {
        private const val TAG = "Museroom"
        private const val RESOLVE_EVERY_MS = 20_000L

        /** How long a token stays interesting after it was last heard. */
        private const val FORGET_AFTER_MS = 90_000L

        @Volatile private var instance: ProximityManager? = null

        fun get(context: Context): ProximityManager =
            instance ?: synchronized(this) {
                instance ?: ProximityManager(context.applicationContext).also { instance = it }
            }
    }
}

sealed interface ProximityStatus {
    data object Off : ProximityStatus
    data object NeedsPermission : ProximityStatus
    data object BluetoothOff : ProximityStatus
    data object Searching : ProximityStatus
    data object PausedForPrivacy : ProximityStatus
    data class Failed(val reason: String) : ProximityStatus
}
