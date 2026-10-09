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

/**
 * The user copies a privacy-filtered local report, not a raw Ktor/OkHttp exception.
 * Diagnostics stay visible in release builds without turning on HTTP body logging.
 */
@Composable
internal fun ConnectionDiagnosticsContent() {
    val diagnostics = (LocalContext.current.applicationContext as? JetMealApplication)?.connectionDiagnostics ?: return
    var expanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            expanded = !expanded
            if (expanded) events = diagnostics.recent()
        }) {
            Text(if (expanded) "Скрыть диагностику подключения" else "Диагностика подключения")
        }
        if (expanded) {
            Text("Ошибки DNS, TCP, TLS, HTTP и время выполнения запросов к Supabase.",
                style = MaterialTheme.typography.bodySmall)
            Text("Записи UTC. layer=OkHttp — сетевая причина до обработки библиотекой; " +
                "phase — этап сбоя; cause — тип ошибки; elapsedMs — время запроса. " +
                "dnsMs, tcpMs, tlsMs — длительность отдельных этапов (если они выполнялись). " +
                "result=recovered означает, что запрос к Supabase снова завершился успешно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                events.lineSequence().filter { it.isNotBlank() }
                    .toList().takeLast(if (showAll) 60 else 12)
                    .joinToString("\n").ifBlank { "Сетевых событий пока нет. Попробуйте обновить дневник." },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { events = diagnostics.recent() }) {
                    Text("Обновить")
                }
                OutlinedButton(onClick = {
                    events = diagnostics.recent()
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(
                            "JetMeal connection diagnostics", events)))
                    }
                }, enabled = events.isNotBlank()) {
                    Text("Скопировать отчёт")
                }
            }
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Последние 12 событий" else "Показать последние 60 событий")
            }
            Text("Диагностика не содержит токены, пароли, URL, IP-адреса или данные питания. " +
                "Первопричину ошибок приложения, не дошедших до OkHttp, определить по таким событиям не всегда возможно.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
