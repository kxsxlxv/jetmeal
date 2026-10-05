package com.kxsxlxv.jetmeal

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

class MainActivity : ComponentActivity() {
    private val model by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                JetMealViewModel((application as JetMealApplication).repository, extras.createSavedStateHandle()) as T
        })[JetMealViewModel::class.java]
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { JetmealTheme { JetMealApp(model) } }
    }
    override fun onResume() { super.onResume(); model.refresh() }
}
