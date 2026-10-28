package com.kxsxlxv.jetmeal.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import com.kxsxlxv.jetmeal.data.lastConnectionEvents
import com.kxsxlxv.jetmeal.data.SupabaseEngineChoice
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Share or save sanitized diagnostic events as UTF-8 text without sending a
 * potentially oversized report through the Android clipboard.
 */
private fun reportFileName(): String {
    val time = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneOffset.UTC).format(Instant.now())
    return "JetMeal-diagnostics-${time}Z.txt"
}

private fun shareReport(context: Context, text: String) {
    val folder = File(context.cacheDir, "connection_reports").apply { mkdirs() }
    // Rotate our own temporary exports, never touch other app cache entries.
    folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(9)?.forEach { it.delete() }
    val file = File(folder, reportFileName()).apply { writeText(text, Charsets.UTF_8) }
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.diagnostics.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "JetMeal diagnostics", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Отправить диагностику Jetmeal"))
}

/** The report includes only allowlisted telemetry, never HTTP headers, tokens or bodies. */
@Composable
internal fun ConnectionDiagnosticsContent() {
    val context = LocalContext.current
    val diagnostics = (context.applicationContext as? JetMealApplication)?.connectionDiagnostics ?: return
    var expanded by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf("") }
    var pendingSave by remember { mutableStateOf("") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val originalEngine = remember { SupabaseEngineChoice.isCio(context) }
    var preferredEngine by remember { mutableStateOf(originalEngine) }
    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            exportStatus = runCatching {
                val stream = requireNotNull(context.contentResolver.openOutputStream(uri))
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(pendingSave) }
                "Отчёт сохранён. Прикрепите файл к сообщению в ChatGPT."
            }.getOrElse { "Не удалось сохранить файл. Попробуйте отправить отчёт через меню «Поделиться»." }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            expanded = !expanded
            if (expanded) events = diagnostics.recent()
        }) {
            Text(if (expanded) "Скрыть диагностику подключения" else "Диагностика подключения")
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Новый сетевой движок (CIO)", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = preferredEngine, onCheckedChange = { chosen ->
                    preferredEngine = chosen
                    SupabaseEngineChoice.setCio(context, chosen)
                })
            }
            Text(
                if (preferredEngine == originalEngine) {
                    "Используется при запуске: ${if (originalEngine) "CIO" else "OkHttp"}."
                } else {
                    "Выбран ${if (preferredEngine) "CIO" else "OkHttp"}. " +
                        "Изменение применится после полной остановки приложения и нового запуска."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ошибки DNS, TCP, TLS, HTTP и время выполнения запросов к Supabase.",
                style = MaterialTheme.typography.bodySmall)
            Text("Записи UTC. layer=OkHttp — сетевая причина до обработки библиотекой; " +
                "phase — этап сбоя; cause — тип ошибки; elapsedMs — время запроса. " +
                "dnsMs, tcpMs, tlsMs — длительность отдельных этапов (если они выполнялись). " +
                "result=recovered означает, что запрос к Supabase снова завершился успешно.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                lastConnectionEvents(events, if (showAll) 60 else 12)
                    .ifBlank { "Сетевых событий пока нет. Попробуйте обновить дневник." },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { events = diagnostics.recent() }) {
                    Text("Обновить")
                }
                OutlinedButton(onClick = {
                    events = diagnostics.recent()
                    val latest = lastConnectionEvents(events, 20)
                    if (latest.isNotBlank()) {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(
                                "JetMeal connection diagnostics (last 20)", latest)))
                        }
                        exportStatus = "Скопированы последние 20 событий."
                    }
                }, enabled = events.isNotBlank()) {
                    Text("Копировать 20")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    events = diagnostics.recent()
                    if (events.isNotBlank()) {
                        exportStatus = runCatching {
                            shareReport(context, events)
                            null
                        }.getOrElse {
                            "Не удалось поделиться отчётом. Попробуйте сохранить .txt."
                        }
                    }
                }, enabled = events.isNotBlank()) {
                    Text("Поделиться .txt")
                }
                OutlinedButton(onClick = {
                    events = diagnostics.recent()
                    if (events.isNotBlank()) {
                        pendingSave = events
                        saveDocument.launch(reportFileName())
                    }
                }, enabled = events.isNotBlank()) {
                    Text("Сохранить .txt")
                }
            }
            exportStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Последние 12 событий" else "Показать последние 60 событий")
            }
            Text("Диагностика не содержит токены, пароли, URL, IP-адреса или данные питания. " +
                "Отправка файла происходит только по вашему действию; автоматически отчёты никуда не загружаются.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
