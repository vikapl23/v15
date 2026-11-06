package com.lab.vibrationanalyzer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lab.vibrationanalyzer.repository.SyncStatus

val CloudOkColor = Color(0xFF2E7D32)     // зелений — останній запит успішний
val CloudErrorColor = Color(0xFFC62828)  // червоний — помилка або Pending
val CloudUnknownColor = Color(0xFF9E9E9E)

fun cloudColor(ok: Boolean?): Color = when (ok) {
    true -> CloudOkColor
    false -> CloudErrorColor
    null -> CloudUnknownColor
}

/**
 * ЛР3. Індикатор «Хмарна синхронізація»: кольорова точка + статус.
 * Використовується і на екрані «Монітор», і на екрані «Хмара».
 */
@Composable
fun CloudSyncBadge(status: SyncStatus, ok: Boolean?, modifier: Modifier = Modifier) {
    val color = cloudColor(ok)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Хмарна синхронізація: ${status.title}",
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}
