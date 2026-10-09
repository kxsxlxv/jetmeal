package com.kxsxlxv.jetmeal

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.kxsxlxv.jetmeal.ui.JetMealApp
import com.kxsxlxv.jetmeal.ui.JetMealViewModel
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val model by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = application as JetMealApplication
                return JetMealViewModel(
                    app.repository,
                    extras.createSavedStateHandle(),
                    app.widgetCoordinator,
                    app.picoocIntegration,
                    app.offlineDiary,
                ) as T
            }
        })[JetMealViewModel::class.java]
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JetmealTheme { JetMealApp(model) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() { super.onResume(); model.refresh() }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_TODAY, false) == true) {
            model.openDate(LocalDate.now())
            // A tap on an existing widget must request a fresh snapshot now;
            // onResume refreshes the foreground diary as well.
            (application as JetMealApplication).widgetCoordinator.requestSync()
            model.refresh()
            intent.removeExtra(EXTRA_OPEN_TODAY)
        }
    }

    companion object {
        const val EXTRA_OPEN_TODAY = "com.kxsxlxv.jetmeal.extra.OPEN_TODAY"
    }
}
