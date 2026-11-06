package com.lab.vibrationanalyzer.cloud

import com.lab.vibrationanalyzer.repository.CloudSyncState

// State — усе, що видно на екрані «Хмара».
data class CloudState(
    val sync: CloudSyncState = CloudSyncState(),
    val isOnline: Boolean = true,
    val syncEnabled: Boolean = true,
    val intervalSec: Int = 5,          // відправка не рідше ніж раз на N секунд
    val delta: Float = 1.0f,           // або при зміні значення на Δ, м/с²
    val deviceId: String = "",
    val usingMockApi: Boolean = false, // true — cloud.url не задано, дані йдуть на JSONPlaceholder
    val lastJson: String? = null       // JSON останнього надісланого запису
)

// Action — дії користувача на екрані «Хмара».
sealed interface CloudAction {
    data class SyncToggled(val enabled: Boolean) : CloudAction
    data class IntervalChanged(val seconds: Int) : CloudAction
    data class DeltaChanged(val delta: Float) : CloudAction
    data object RetryClick : CloudAction
}

// Event — разові повідомлення. Показуються як Snackbar на будь-якій вкладці.
sealed interface CloudEvent {
    data class ShowSnackbar(val text: String) : CloudEvent
}
