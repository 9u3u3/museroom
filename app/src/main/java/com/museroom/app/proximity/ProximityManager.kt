package com.museroom.app.proximity

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.museroom.app.net.NearbyListener
import com.museroom.app.net.ProximityApi
import com.museroom.app.net.ServerClock
import com.museroom.app.privacy.PrivacyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Enough to tell the six different failures apart. "Nothing found" can mean
     * the radio never started, nobody is in range, or the people in range are not
     * playing anything, and those want different answers.
     */
    private val _diagnostics = MutableStateFlow(ProximityDiagnostics())
    val diagnostics: StateFlow<ProximityDiagnostics> = _diagnostics.asStateFlow()

    private val seen = mutableMapOf<String, Long>()
    private var scope: CoroutineScope? = null
    private var advertising = false

    /**
     * Somebody new is on the air, so there is a reason to ask who.
     *
     * Conflated: three phones walking in together are one reason to ask, not
     * three. Hearing a beacon we already knew about is no reason at all, which
     * matters because a beacon is heard several times a second.
     */
    private val heard = Channel<Unit>(Channel.CONFLATED)

    /**
     * Whether somebody is actually watching the Nearby screen.
     *
     * Discovery is a thing people stand there waiting for, so it is worth the
     * battery while they are waiting and not worth it afterwards.
     */
    @Volatile
    private var foreground = false

    /** Who resolved most recently, and when, so the list does not flicker. */
    private val holding = mutableMapOf<String, Pair<NearbyListener, Long>>()

    /**
     * The radio going off takes the scan with it and says nothing. Coming back
     * does not restore it either, so both have to be watched for.
     */
    private val radioWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (scope != null) {
                    restartScanning()
                    _state.value = ProximityStatus.Searching
                }
                BluetoothAdapter.STATE_OFF -> if (scope != null) {
                    _diagnostics.value = _diagnostics.value.copy(scanning = false, advertising = false)
                    _state.value = ProximityStatus.BluetoothOff
                }
            }
        }
    }
    private var watchingRadio = false

    private val adapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            _diagnostics.value = _diagnostics.value.copy(advertising = false)
            _state.value = ProximityStatus.Failed(describeAdvertiseError(errorCode))
        }

        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _diagnostics.value = _diagnostics.value.copy(advertising = true)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val payload = result?.scanRecord?.getManufacturerSpecificData(Beacon.MANUFACTURER_ID)
            val token = Beacon.tokenFrom(payload) ?: return
            val isNew = synchronized(seen) {
                val first = token !in seen
                seen[token] = SystemClock.elapsedRealtime()
                first
            }
            _diagnostics.value = _diagnostics.value.copy(
                beaconsHeard = synchronized(seen) { seen.size },
                lastHeardAtMs = System.currentTimeMillis(),
            )
            // Somebody walked in. Waiting out the rest of the interval before
            // asking who they are is most of the time this used to take.
            if (isNew) heard.trySend(Unit)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _diagnostics.value = _diagnostics.value.copy(scanning = false)
            _state.value = ProximityStatus.Failed("Could not scan (code $errorCode).")
        }
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    /**
     * Everything this needs, which differs sharply across Android versions.
     *
     * BLUETOOTH_CONNECT is in the list for one reason: raising the system's
     * own "turn Bluetooth on" dialog needs it from Android 12, and launching
     * that dialog without it does not fail quietly — it throws, and takes the
     * app down. Nothing here ever connects to a device. All three sit in the
     * same Nearby devices prompt, so asking for it costs nobody a second tap.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
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

        if (!watchingRadio) {
            runCatching {
                context.registerReceiver(
                    radioWatch,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                )
            }.onSuccess { watchingRadio = true }
        }

        newScope.launch { api.setEnabled(true) }
        // A beacon says when it stops being valid, and the server decides
        // whether that moment has passed. Two phones disagreeing about the
        // time is the difference between being findable and not.
        newScope.launch { ServerClock.sync() }
        newScope.launch { rotateForever() }
        newScope.launch { resolveForever() }
        newScope.launch { rescanForever() }
        startScanning()
    }

    /**
     * Whether the Nearby screen is in front of somebody.
     *
     * Both radios run at their fastest while it is, because that is when a
     * person is standing there wondering why their friend has not appeared,
     * and slower the rest of the time, because then nobody is waiting.
     */
    fun setForeground(watching: Boolean) {
        if (foreground == watching) return
        foreground = watching
        // Scan settings cannot be changed under a running scan.
        if (scope != null) restartScanning()
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        stopAdvertising()
        stopScanning()
        synchronized(seen) { seen.clear() }
        synchronized(holding) { holding.clear() }
        _nearby.value = emptyList()
        if (watchingRadio) {
            runCatching { context.unregisterReceiver(radioWatch) }
            watchingRadio = false
        }
        _diagnostics.value = ProximityDiagnostics()
        _state.value = ProximityStatus.Off

        // Withdrawing matters: leaving a live beacon behind would keep answering
        // for someone who has turned the feature off.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            api.setEnabled(false)
            api.withdraw()
        }
    }

    /**
     * Keeping a live token on the air, and getting back on it after a failure.
     *
     * This used to sleep for the full rotation whatever happened, which meant a
     * single dropped request at switch-on left somebody invisible for fifteen
     * minutes with the switch showing "on". One bad moment on a train platform
     * cost the entire time they were standing there.
     *
     * Private session is watched rather than sampled, for the same reason in
     * reverse: turning it off should make you findable now, not at whatever
     * point the rotation next came round.
     */
    private suspend fun rotateForever() {
        var retryIn = FIRST_RETRY_MS
        while (true) {
            if (privacy.privateSession.value) {
                // Somebody who has stopped recording has plainly not agreed to
                // being found either.
                stopAdvertising()
                _state.value = ProximityStatus.PausedForPrivacy
                privacy.privateSession.first { !it }
                continue
            }

            val token = Beacon.newToken()
            val published = api.publish(token, ServerClock.nowMs() + Beacon.LIFETIME_MS)
            if (published.isSuccess) {
                startAdvertising(token)
                if (_state.value !is ProximityStatus.Failed) {
                    _state.value = ProximityStatus.Searching
                }
                retryIn = FIRST_RETRY_MS
                // Sleep until the token is due to roll, or until somebody
                // turns private session on, whichever comes first.
                withTimeoutOrNull(Beacon.ROTATE_AFTER_MS) {
                    privacy.privateSession.first { it }
                }
            } else {
                val why = published.exceptionOrNull()?.message ?: "Publish failed"
                _state.value = ProximityStatus.Failed(why)
                delay(retryIn)
                retryIn = (retryIn * 2).coerceAtMost(LONGEST_RETRY_MS)
            }
        }
    }

    /**
     * Turning what we overheard into people.
     *
     * Asking first and waiting afterwards, which sounds like a detail and was
     * twenty seconds of every discovery: the wait used to come first, so the
     * fastest anybody could ever appear was one full interval after they were
     * heard. The wait now ends early whenever a beacon we have not heard
     * before turns up.
     */
    private suspend fun resolveForever() {
        while (true) {
            val fresh = synchronized(seen) {
                val cutoff = SystemClock.elapsedRealtime() - FORGET_AFTER_MS
                seen.entries.removeAll { it.value < cutoff }
                seen.keys.toList()
            }

            if (fresh.isEmpty()) {
                // Nothing on the air. Still worth ageing the list, or somebody
                // who walked out would sit there until somebody else walked in.
                hold(emptyList())
            } else {
                api.resolve(fresh)
                    .onSuccess {
                        hold(it)
                        _diagnostics.value = _diagnostics.value.copy(
                            lastResolveAtMs = System.currentTimeMillis(),
                            lastResolveCount = it.size,
                            lastResolveError = null,
                        )
                    }
                    .onFailure {
                        // One failed request says nothing about who is in the
                        // room, so the list is left exactly as it was rather
                        // than aged towards empty on no evidence.
                        _diagnostics.value = _diagnostics.value.copy(lastResolveError = it.message)
                    }
            }

            withTimeoutOrNull(RESOLVE_EVERY_MS) { heard.receive() }
        }
    }

    /**
     * The list, with a little memory.
     *
     * A single resolve that misses somebody used to take them off the screen
     * and the next one put them back, which reads as the app not working.
     *
     * The memory is deliberately short. Nearby lists people who are playing
     * something, so falling out of an answer is usually the truth rather than
     * a glitch: they paused, or a track ended. Long enough to cover the gap
     * between two songs and one unlucky request, and no longer, because a list
     * that keeps somebody who has stopped is lying about who you could join.
     */
    private fun hold(found: List<NearbyListener>) {
        val now = SystemClock.elapsedRealtime()
        val current = synchronized(holding) {
            found.forEach { holding[it.userId] = it to now }
            holding.entries.removeAll { now - it.value.second > HOLD_MS }
            holding.values.sortedByDescending { it.second }.map { it.first }
        }
        _nearby.value = current
    }

    private fun startAdvertising(token: String) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            _diagnostics.value = _diagnostics.value.copy(advertising = false, canAdvertise = false)
            _state.value = ProximityStatus.Failed(
                "This phone cannot broadcast over Bluetooth, so others will not see you. " +
                    "You can still see them.",
            )
            return
        }
        stopAdvertising()
        val settings = AdvertiseSettings.Builder()
            // A hundred milliseconds between advertisements rather than two
            // hundred and fifty. The other phone can only hear us during its
            // own scan window, so how often we speak decides how likely that
            // window is to contain us.
            .setAdvertiseMode(
                if (foreground) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(Beacon.MANUFACTURER_ID, Beacon.payload(token))
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onSuccess {
                advertising = true
                _diagnostics.value = _diagnostics.value.copy(tokenPublished = true)
            }
            .onFailure {
                _diagnostics.value = _diagnostics.value.copy(advertising = false)
            }
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
            // Listening without a gap while somebody is watching for a name to
            // appear. Balanced listens about a quarter of the time, which on
            // its own turns a two-second discovery into an eight-second one.
            .setScanMode(
                if (foreground) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED,
            )
            // Both stated rather than left to the platform: batching results
            // would add a delay of its own to the one thing that has to be
            // prompt, and only the first sighting of a beacon is interesting.
            .setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess { _diagnostics.value = _diagnostics.value.copy(scanning = true) }
            .onFailure { _diagnostics.value = _diagnostics.value.copy(scanning = false) }
    }

    private fun restartScanning() {
        stopScanning()
        startScanning()
    }

    /**
     * Starting the scan again, on a timer, for no visible reason.
     *
     * Android quietly demotes a scan that has been running for about half an
     * hour to opportunistic, where it stops looking on its own account and
     * only reports what some other app's scan happens to find. Nothing is
     * reported when this happens; the callback simply goes quiet, which looks
     * exactly like an empty room. Nearby left open on a table would work for
     * half an hour and then never find anybody again.
     */
    private suspend fun rescanForever() {
        while (true) {
            delay(RESCAN_EVERY_MS)
            if (bluetoothReady()) restartScanning()
        }
    }

    private fun stopScanning() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    companion object {
        private const val TAG = "Museroom"
        /** The longest anybody waits when nothing new is being overheard. */
        private const val RESOLVE_EVERY_MS = 20_000L

        /** How long a token stays interesting after it was last heard. */
        private const val FORGET_AFTER_MS = 90_000L

        /**
         * How long somebody stays on the list after dropping out of an answer.
         * Long enough for the gap between two tracks, short enough that it is
         * never showing you a room you could not actually join.
         */
        private const val HOLD_MS = 20_000L

        /** A failed publish is worth trying again in seconds, not minutes. */
        private const val FIRST_RETRY_MS = 2_000L
        private const val LONGEST_RETRY_MS = 60_000L

        /** Comfortably inside Android's undocumented half-hour demotion. */
        private const val RESCAN_EVERY_MS = 15 * 60 * 1000L

        @Volatile private var instance: ProximityManager? = null

        fun get(context: Context): ProximityManager =
            instance ?: synchronized(this) {
                instance ?: ProximityManager(context.applicationContext).also { instance = it }
            }
    }
}

/** Why nothing is showing up. */
data class ProximityDiagnostics(
    /** Our own token is on the air. */
    val advertising: Boolean = false,
    /** The radio is listening. */
    val scanning: Boolean = false,
    /** Some phones cannot act as a peripheral at all. */
    val canAdvertise: Boolean = true,
    val tokenPublished: Boolean = false,
    /** Beacons overheard, before the server says who they belong to. */
    val beaconsHeard: Int = 0,
    val lastHeardAtMs: Long = 0,
    val lastResolveAtMs: Long = 0,
    val lastResolveCount: Int = 0,
    val lastResolveError: String? = null,
)

private fun describeAdvertiseError(code: Int): String = when (code) {
    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
        "This phone cannot broadcast over Bluetooth. You will still see others."
    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
        "Bluetooth is busy with other apps. Try again in a moment."
    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
        "Broadcast rejected as too large."
    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already broadcasting."
    else -> "Could not broadcast (code $code)."
}

sealed interface ProximityStatus {
    data object Off : ProximityStatus
    data object NeedsPermission : ProximityStatus
    data object BluetoothOff : ProximityStatus
    data object Searching : ProximityStatus
    data object PausedForPrivacy : ProximityStatus
    data class Failed(val reason: String) : ProximityStatus
}
