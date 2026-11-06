package com.lab.vibrationanalyzer.helpers.actuator

import kotlinx.coroutines.flow.StateFlow

/**
 * ЛР4. Інтерфейс актуатор-сервісу. ViewModel знає лише його,
 * тому в тестах замість справжніх вібромотора і ліхтарика підставляється фейк.
 */
interface ISmartActuator {
    /** Чи є на пристрої спалах (PackageManager.FEATURE_CAMERA_FLASH). */
    val hasFlash: Boolean

    /** Чи є вібромотор. */
    val hasVibrator: Boolean

    /**
     * Справжній стан ліхтарика — зворотний зв'язок від апаратури (TorchCallback).
     * Якщо спалаху немає, тут стан віртуального ліхтарика.
     */
    val torchOn: StateFlow<Boolean>

    /** Увімкнути «сирену»: серія вібраційних імпульсів. */
    fun triggerAlarm()

    /** Зупинити вібрацію. */
    fun stopAlarm()

    /** Увімкнути/вимкнути ліхтарик. Повертає true, якщо команду виконала апаратура. */
    fun toggleFlashlight(status: Boolean): Boolean

    /** Звільнити ресурси: вимкнути ліхтарик і вібрацію, зняти callback. */
    fun release()
}
