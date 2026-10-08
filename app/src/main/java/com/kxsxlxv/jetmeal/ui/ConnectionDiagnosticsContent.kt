package com.kxsxlxv.jetmeal.ui

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.JetMealApplication
import kotlinx.coroutines.launch

/** Available in release builds as well: copy is explicit and contains only safe local metadata. */
@Composable
internal fun ConnectionDiagnosticsContent() {
    val diagnostics = (LocalContext.current.applicationContext as? JetMealApplication)?.connectionDiagnostics ?: return
    var expanded by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded; if (expanded) events = diagnostics.recent() }) {
            Text(if (expanded) "Скрыть диагностику подключения" else "Диагностика подключения")
        }
        if (expanded) {
            Text("Время UTC, тип ошибки, ответ сервиса и состояние сети.",
                style = MaterialTheme.typography.bodySmall)
            Text(events.lineSequence().takeLastLines(5).ifBlank { "Ошибок подключения пока не записано." },
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = {
                events = diagnostics.recent()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("JetMeal connection diagnostics", events))) }
            }, enabled = events.isNotBlank()) { Text("Скопировать диагностику") }
        }
    }
}

private fun Sequence<String>.takeLastLines(count: Int): String = toList().takeLast(count).joinToString("\n")
