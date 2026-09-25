package app.truenascompanion.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.truenascompanion.AppContainer
import app.truenascompanion.TrueNasApp

@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM =
    viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create((this[APPLICATION_KEY] as TrueNasApp).container) }
        },
    )
