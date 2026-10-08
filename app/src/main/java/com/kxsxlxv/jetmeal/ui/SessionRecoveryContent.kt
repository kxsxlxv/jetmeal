@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** A saved session awaiting connectivity must never masquerade as a password-login screen. */
@Composable
internal fun SessionRecoveryContent(message: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LoadingIndicator()
            Text("Восстанавливаем соединение", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) },
                shapes = ButtonDefaults.shapes()) {
                Text("Настройки подключения")
            }
            ConnectionDiagnosticsContent()
        }
    }
}
