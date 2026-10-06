@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.kxsxlxv.jetmeal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kxsxlxv.jetmeal.data.SignInValidation

@Composable
internal fun AuthContent(state: AppState, signIn: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must not enter saved state, ViewModel state, preferences, or logs.
    val password = remember { TextFieldState() }
    val canSubmit = !state.busy && SignInValidation.canSubmit(email, password.text.toString())
    val submit = { if (canSubmit) signIn(email.trim(), password.text.toString()) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("JetMeal", style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(28.dp))
        Text("Ваше питание.\nВаш ритм.", style = MaterialTheme.typography.headlineLargeEmphasized)
        Spacer(Modifier.height(12.dp))
        Text("Войдите, чтобы открыть свой дневник питания.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(email, { email = it }, label = { Text("Электронная почта") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                shape = TextFieldDefaults.roundedShape, colors = TextFieldDefaults.tonalColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            SecureTextField(password, label = { Text("Пароль") },
                modifier = Modifier.fillMaxWidth(), enabled = !state.busy,
                shape = TextFieldDefaults.roundedShape, colors = TextFieldDefaults.tonalColors(),
                textObfuscationMode = TextObfuscationMode.Hidden,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false, imeAction = ImeAction.Done),
                onKeyboardAction = { submit() })
            Button(onClick = submit, shapes = ButtonDefaults.shapes(), enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Войти") }
            state.error?.let { EditorError(it) }
            if (state.busy) LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
