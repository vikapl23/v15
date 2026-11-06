package com.lab.vibrationanalyzer.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Одне місце для даних вібрації.
 *
 * Зараз (ЛР2) репозиторій лише тримає останній відлік у пам'яті:
 * MonitorViewModel його публікує, HubViewModel читає і відправляє на смарт-хаб.
 * У наступних лабораторних сюди додаються Room (локальна БД) і хмара,
 * а ViewModel та екрани при цьому не змінюються.
 */
class VibrationRepository {

    private val _latest = MutableStateFlow<VibrationReading?>(null)
    val latest: StateFlow<VibrationReading?> = _latest.asStateFlow()

    fun publish(reading: VibrationReading) {
        _latest.value = reading
    }
}
