package ru.e90pult.safety

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.e90pult.data.Prefs
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Состояние защиты от случайных действий в движении. */
data class SafetyState(
    val gpsWanted: Boolean = true,
    val gpsPermission: Boolean = false,
    val gpsProviderOn: Boolean = false,
    val speedKmh: Float? = null,
    val lastFixAt: Long = 0,
    /** Скорость выше порога за последние 30 секунд. */
    val moving: Boolean = false,
    val btWanted: Boolean = true,
    /** Хотя бы в одном профиле выбрано устройство машины. */
    val btConfigured: Boolean = false,
    val btPermission: Boolean = false,
    /** Телефон подключён по Bluetooth к устройству машины (любого профиля). */
    val inCar: Boolean = false,
    /** Устройство, к которому подключён телефон; если не подключён — выбранные устройства. */
    val btName: String = "",
    /** Bluetooth телефона включён (иначе подключение к машине не определить). */
    val btAdapterOn: Boolean = false
) {
    /** Есть свежая скорость по GPS — можно достоверно судить, стоит ли машина. */
    fun gpsFresh(now: Long = System.currentTimeMillis()): Boolean = speedKmh != null && now - lastFixAt < 30_000

    /** Ни GPS, ни Bluetooth не дают сведений — нужно подтверждение «машина стоит». */
    fun unknown(now: Long = System.currentTimeMillis()): Boolean =
        !gpsFresh(now) && !(btWanted && btConfigured && btPermission && btAdapterOn)
}

/**
 * Определение движения: скорость по GPS телефона (только пока приложение на экране)
 * и подключение по Bluetooth к устройству машины. Учитываются устройства всех профилей:
 * телефон в любой из машин — значит, возможно, едет, какую бы машину ни выбрали в приложении.
 */
class SafetyMonitor(private val context: Context, private val prefs: Prefs) {

    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var gpsActive = false
    private var receiverRegistered = false
    private var lastMovingAt = 0L
    private var prevLoc: Location? = null
    /** Устройства машин: адрес в верхнем регистре → имя. Доступ — только из главного потока. */
    private var carDevices: Map<String, String> = emptyMap()
    /** Подключённые сейчас устройства машин. */
    private val connected = mutableSetOf<String>()
    /** Номер опроса Bluetooth: ответы старых опросов (например, пришедшие после ухода с экрана) отбрасываются. */
    private var btGeneration = 0

    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        // На Android 9 и ниже эти методы абстрактные — реализуем явно.
        override fun onProviderEnabled(provider: String) = refreshGpsFlags()
        override fun onProviderDisabled(provider: String) = refreshGpsFlags()
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val ticker = object : Runnable {
        override fun run() {
            recompute()
            if (started) main.postDelayed(this, 5_000)
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF) == BluetoothAdapter.STATE_ON
                _state.update { it.copy(btAdapterOn = on) }
                if (on) {
                    refreshBt()
                } else {
                    connected.clear()
                    publishBt()
                }
                return
            }
            val addr = try { dev?.address } catch (e: SecurityException) { null } ?: return
            val key = addr.uppercase()
            if (key !in carDevices) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connected.add(key)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> connected.remove(key)
                else -> return
            }
            publishBt()
        }
    }

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBtPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Устройства машин из всех профилей: (адрес, имя). Вызывать из главного потока. */
    fun setCarDevices(devices: List<Pair<String, String>>) {
        val map = devices.filter { it.first.isNotBlank() }
            .associate { (addr, name) -> addr.uppercase() to name.ifBlank { addr } }
        if (map == carDevices) return
        carDevices = map
        connected.retainAll(map.keys)
        _state.update { it.copy(btConfigured = map.isNotEmpty()) }
        publishBt()
        if (started) refreshBt()
    }

    private fun publishBt() {
        val first = connected.firstOrNull()
        _state.update {
            it.copy(
                inCar = first != null,
                btName = first?.let { k -> carDevices[k] } ?: carDevices.values.joinToString(", ")
            )
        }
    }

    /** Вызывать из onStart активности. */
    fun start() {
        if (started) {
            restart()
            return
        }
        started = true
        if (!receiverRegistered) {
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(btReceiver, f, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(btReceiver, f)
                }
                receiverRegistered = true
            } catch (e: Exception) {
                receiverRegistered = false
            }
        }
        restart()
        main.post(ticker)
    }

    /** Вызывать из onStop активности: GPS работает только пока приложение на экране. */
    fun stop() {
        started = false
        main.removeCallbacks(ticker)
        stopGps()
        if (receiverRegistered) {
            try { context.unregisterReceiver(btReceiver) } catch (e: Exception) { }
            receiverRegistered = false
        }
        // Вне экрана данные устаревают: после возврата всё определяется заново.
        lastMovingAt = 0L
        btGeneration++
        connected.clear()
        _state.update { it.copy(moving = false, speedKmh = null) }
        publishBt()
    }

    /** Перечитать настройки и разрешения (после выдачи разрешения или смены настроек). */
    fun restart() {
        val s = prefs.value
        _state.update {
            it.copy(
                gpsWanted = s.motionGps,
                btWanted = s.motionBt,
                gpsPermission = hasLocationPermission(),
                btPermission = hasBtPermission(),
                btConfigured = carDevices.isNotEmpty()
            )
        }
        if (!started) return
        if (s.motionGps && hasLocationPermission()) startGps() else stopGps()
        if (s.motionBt) {
            refreshBt()
        } else {
            connected.clear()
            publishBt()
        }
        refreshGpsFlags()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsActive) return
        val m = lm ?: return
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            gpsActive = true
        } catch (e: SecurityException) {
            gpsActive = false
        } catch (e: IllegalArgumentException) {
            gpsActive = false // GPS нет в устройстве
        }
    }

    private fun stopGps() {
        if (!gpsActive) return
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
        gpsActive = false
        prevLoc = null
        _state.update { it.copy(speedKmh = null) }
    }

    private fun refreshGpsFlags() {
        val on = try { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true } catch (e: Exception) { false }
        _state.update { it.copy(gpsProviderOn = on, gpsPermission = hasLocationPermission()) }
    }

    private fun onLocation(l: Location) {
        val now = System.currentTimeMillis()
        var speed: Float? = if (l.hasSpeed()) l.speed * 3.6f else null
        val prev = prevLoc
        if (speed == null && prev != null) {
            val dt = (l.time - prev.time) / 1000f
            if (dt in 1f..30f) speed = prev.distanceTo(l) / dt * 3.6f
        }
        prevLoc = l
        val threshold = prefs.value.speedThresholdKmh.coerceIn(3, 60)
        if (speed != null && speed > threshold) lastMovingAt = now
        _state.update { it.copy(speedKmh = speed, lastFixAt = now) }
        recompute()
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        val moving = lastMovingAt > 0 && now - lastMovingAt < 30_000
        _state.update { st ->
            st.copy(moving = moving, speedKmh = if (now - st.lastFixAt > 30_000) null else st.speedKmh)
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBt() {
        if (carDevices.isEmpty() || !hasBtPermission()) {
            connected.clear()
            publishBt()
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val enabled = try { adapter?.isEnabled == true } catch (e: SecurityException) { false }
        _state.update { it.copy(btAdapterOn = enabled) }
        if (adapter == null || !enabled) {
            connected.clear()
            publishBt()
            return
        }
        val targets = carDevices.keys
        val gen = ++btGeneration
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val found = try {
                            proxy.connectedDevices.map { it.address.uppercase() }.filter { it in targets }
                        } catch (e: SecurityException) {
                            emptyList()
                        }
                        if (found.isNotEmpty()) {
                            main.post {
                                if (started && gen == btGeneration) {
                                    connected.addAll(found.filter { it in carDevices })
                                    publishBt()
                                }
                            }
                        }
                        try { adapter.closeProfileProxy(p, proxy) } catch (e: Exception) { }
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            } catch (e: SecurityException) {
                // нет разрешения — остаётся «не в машине»
            }
        }
    }

    /** Сопряжённые Bluetooth-устройства: имя → адрес. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Pair<String, String>> {
        if (!hasBtPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { (it.name ?: it.address) to it.address }.sortedBy { it.first }
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}

/** ПИН-код входа в «Критичное»: PBKDF2-SHA256 с солью. Счётчик ошибок переживает перезапуск. */
class PinStore(private val prefs: Prefs) {

    sealed class Check {
        object Ok : Check()
        data class Wrong(val attemptsLeft: Int) : Check()
        data class Locked(val seconds: Int) : Check()
    }

    private val _unlockedUntil = MutableStateFlow(0L)
    /** До какого момента открыт раздел «Критичное» (0 — закрыт). Экраны подписываются на это значение. */
    val unlockedUntil: StateFlow<Long> = _unlockedUntil.asStateFlow()

    fun hasPin(): Boolean = prefs.value.hasPin

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val enc = Base64.getEncoder()
        prefs.update {
            it.copy(pinHash = enc.encodeToString(hash(pin, salt)), pinSalt = enc.encodeToString(salt), pinFails = 0, pinLockedUntil = 0)
        }
    }

    fun clearPin() {
        prefs.update { it.copy(pinHash = "", pinSalt = "", pinFails = 0, pinLockedUntil = 0) }
        lock()
    }

    /**
     * Проверка ПИН. После 5 неверных вводов подряд — пауза 30 с, каждая следующая ошибка удваивает её (до часа).
     */
    @Synchronized
    fun check(pin: String): Check {
        val now = System.currentTimeMillis()
        val s = prefs.value
        if (now < s.pinLockedUntil) return Check.Locked(((s.pinLockedUntil - now) / 1000 + 1).toInt())
        if (!s.hasPin) return Check.Ok
        val ok = try {
            val dec = Base64.getDecoder()
            hash(pin, dec.decode(s.pinSalt)).contentEquals(dec.decode(s.pinHash))
        } catch (e: Exception) {
            false
        }
        if (ok) {
            prefs.update { it.copy(pinFails = 0, pinLockedUntil = 0) }
            return Check.Ok
        }
        val fails = s.pinFails + 1
        if (fails >= 5) {
            val seconds = (30L shl (fails - 5).coerceAtMost(7)).coerceAtMost(3600L)
            prefs.update { it.copy(pinFails = fails, pinLockedUntil = now + seconds * 1000) }
            return Check.Locked(seconds.toInt())
        }
        prefs.update { it.copy(pinFails = fails) }
        return Check.Wrong(5 - fails)
    }

    /** Раздел «Критичное» открыт на 5 минут или до ухода приложения с экрана. */
    fun unlock() {
        _unlockedUntil.value = System.currentTimeMillis() + 300_000
    }

    fun isUnlocked(): Boolean = System.currentTimeMillis() < _unlockedUntil.value

    fun lock() {
        _unlockedUntil.value = 0L
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

/** Отпечаток/лицо через системный BiometricPrompt (Android 9+). */
object BiometricGate {

    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return when {
            Build.VERSION.SDK_INT >= 30 -> {
                val bm = context.getSystemService(BiometricManager::class.java) ?: return false
                bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            }
            Build.VERSION.SDK_INT == 29 -> {
                val bm = context.getSystemService(BiometricManager::class.java) ?: return false
                @Suppress("DEPRECATION")
                bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
            else -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }
    }

    @RequiresApi(28)
    fun prompt(activity: Activity, title: String, onOk: () -> Unit, onFallback: () -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Или введите ПИН-код приложения")
            .setNegativeButton("ПИН-код", activity.mainExecutor) { _, _ -> onFallback() }
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFallback()
        })
    }
}
