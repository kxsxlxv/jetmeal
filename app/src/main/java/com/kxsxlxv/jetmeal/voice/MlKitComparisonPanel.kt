package com.kxsxlxv.jetmeal.voice

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MlKitComparisonPanel(
    recording: Boolean,
    running: Boolean,
    audio: RecordedPcm?,
    results: List<MlKitComparisonResult>,
    status: String,
    canStart: Boolean,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onRun: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Basic vs Advanced · одна запись", style = MaterialTheme.typography.titleLarge)
        Text(
            "Запишите фразу один раз, затем сравните два режима на одних и тех же "+
                "байтах аудио. Google требует подавать PCM в реальном времени, "+
                "поэтому общее время будет близко к длительности записи для каждого режима.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(
                enabled=canStart && !running,
                onClick=if(recording) onStop else onRecord,
            ) { Text(if(recording) "Стоп" else "Записать фразу") }
            Button(
                onClick=onRun,
                enabled=canStart && !running && !recording && audio!=null,
            ) { Text(if(running) "Сравниваю…" else "Сравнить") }
        }
        if(audio!=null) {
            Text(
                "PCM: "+audio.durationMs+" мс · "+audio.bytes+
                    " байт · 16 кГц / mono / 16 бит",
                style=MaterialTheme.typography.labelSmall,
            )
        }
        val displayed=if(results.isNotEmpty()) results
        else MlKitMode.entries.map { MlKitComparisonResult(it,"Ещё не запускался") }
        displayed.forEach { run ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(run.mode.label,style=MaterialTheme.typography.titleMedium)
                    Text(run.status,style=MaterialTheme.typography.bodySmall)
                    run.transcript?.let {
                        Text(it,style=MaterialTheme.typography.bodyLarge)
                    }
                    run.modelCheckMs?.let { Text("Проверка модели: "+it+" мс") }
                    run.downloadMs?.let { Text("Загрузка модели: "+it+" мс") }
                    run.firstPartialMs?.let { Text("Первый промежуточный текст: "+it+" мс") }
                    run.firstFinalMs?.let { Text("Первый финальный фрагмент: "+it+" мс") }
                    run.recognitionMs?.let { Text("Распознавание (включая подачу звука): "+it+" мс") }
                    if(run.totalBytes>0) {
                        Text("Передано аудио: "+run.bytesWritten+" / "+run.totalBytes+
                            " байт · EOF: "+run.streamFinished,
                            style=MaterialTheme.typography.labelSmall)
                    }
                    if(run.recognitionMs!=null) {
                        Text("Финальных сегментов: "+run.finalSegments+
                            " · обновлений текста: "+run.partialUpdates,
                            style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            "Сравниваются запрошенные MODE_BASIC и MODE_ADVANCED. "+
                "API не сообщает название реально выбранной модели Google. "+
                "При отсутствии финального текста выводится ошибка, а не выдуманная транскрипция.",
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
