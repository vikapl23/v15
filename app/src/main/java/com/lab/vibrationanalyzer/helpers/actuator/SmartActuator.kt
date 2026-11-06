package com.lab.vibrationanalyzer.helpers.actuator

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ЛР4. Актуатор-сервіс: апаратні ресурси смартфона як імітація промислових виконавчих пристроїв.
 *
 *  - «Сирена»  → вібромотор: VibratorManager (API 31+) або Vibrator (API < 31).
 *  - «Прожектор» → спалах камери: CameraManager.setTorchMode().
 *
 * Перед кожним викликом перевіряємо, чи є апаратна можливість
 * (hasSystemFeature(FEATURE_CAMERA_FLASH), hasVibrator()). В емуляторі спалаху
 * зазвичай немає — тоді ліхтарик працює віртуально: стан змінюється лише в UI і Logcat,
 * а додаток не падає (Error Handling).
 */
class SmartActuator(context: Context) : ISmartActuator {

    private val appContext = context.applicationContext

    // ---------- Вібрація ----------

    private val vibratorManager: VibratorManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)
        } else {
            null
        }

    /** Для API < 31 — старий Vibrator. Наш minSdk = 31, але гілку лишаємо, як просить методичка. */
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        vibratorManager?.defaultVibrator
            ?: appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    override val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    // ---------- Ліхтарик ----------

    private val cameraManager: CameraManager? = appContext.getSystemService(CameraManager::class.java)

    override val hasFlash: Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)

    /** id камери, у якої є спалах (зазвичай задня, "0"). */
    private val torchCameraId: String? = if (hasFlash) findFlashCamera() else null

    private val _torchOn = MutableStateFlow(false)
    override val torchOn: StateFlow<Boolean> = _torchOn.asStateFlow()

    /**
     * Зворотний зв'язок від апаратури: Android сам повідомляє, коли ліхтарик реально
     * ввімкнувся чи вимкнувся (зокрема, якщо його вимкнули зі шторки).
     */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _torchOn.value = enabled
                Log.d(TAG, "TorchCallback: ліхтарик ${if (enabled) "УВІМК" else "ВИМК"}")
            }
        }
    }

    init {
        if (torchCameraId != null) {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }
        Log.d(TAG, "актуатори: спалах=${hasFlash} (camera=$torchCameraId), вібромотор=$hasVibrator")
    }

    override fun triggerAlarm() {
        val v = vibrator
        if (v == null || !hasVibrator) {
            Log.w(TAG, "triggerAlarm: вібромотора немає — сирена лише на екрані")
            return
        }
        // Патерн «сирени»: пауза 0, 400 мс вібро, 200 пауза, 400 вібро, 200 пауза, 800 вібро
        val effect = VibrationEffect.createWaveform(ALARM_PATTERN, -1)   // -1 — без повтору
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
                vibratorManager.vibrate(CombinedVibration.createParallel(effect))
            } else {
                v.vibrate(effect)
            }
            Log.d(TAG, "triggerAlarm: вібросигнал ${ALARM_PATTERN.joinToString("/")} мс")
        } catch (e: SecurityException) {
            Log.e(TAG, "triggerAlarm: немає дозволу VIBRATE", e)
        }
    }

    override fun stopAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && vibratorManager != null) {
            vibratorManager.cancel()
        } else {
            vibrator?.cancel()
        }
        Log.d(TAG, "stopAlarm: вібрацію зупинено")
    }

    override fun toggleFlashlight(status: Boolean): Boolean {
        val id = torchCameraId
        val manager = cameraManager
        if (id == null || manager == null) {
            // Немає спалаху (емулятор) — віртуальний ліхтарик
            _torchOn.value = status
            Log.w(TAG, "toggleFlashlight($status): FEATURE_CAMERA_FLASH немає — віртуальний режим")
            return false
        }
        return try {
            manager.setTorchMode(id, status)   // результат прийде в torchCallback
            Log.d(TAG, "setTorchMode(camera=$id, $status)")
            true
        } catch (e: CameraAccessException) {
            // Камера зайнята іншим додатком або вимкнена політикою
            Log.e(TAG, "setTorchMode: камера недоступна (${e.reason})", e)
            _torchOn.value = status
            false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "setTorchMode: некоректний cameraId=$id", e)
            _torchOn.value = status
            false
        }
    }

    override fun release() {
        if (_torchOn.value) toggleFlashlight(false)
        stopAlarm()
        if (torchCameraId != null) cameraManager?.unregisterTorchCallback(torchCallback)
    }

    private fun findFlashCamera(): String? = try {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (e: CameraAccessException) {
        Log.e(TAG, "не вдалося отримати список камер", e)
        null
    }

    private companion object {
        const val TAG = "ACTUATOR"
        val ALARM_PATTERN = longArrayOf(0, 400, 200, 400, 200, 800)
    }
}
