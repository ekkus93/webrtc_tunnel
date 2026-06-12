package com.phillipchin.webrtctunnel.viewmodel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phillipchin.webrtctunnel.data.AppDependencies

/**
 * Builds a [ViewModelProvider.Factory] for the app's ViewModels using the AndroidX
 * `viewModelFactory {}` DSL. This keeps ViewModel creation owned by Android's
 * `ViewModelProvider` (so `viewModelScope` is lifecycle-bound) without the unchecked
 * cast the manual `create(modelClass)` form requires.
 */
fun appViewModelFactory(deps: AppDependencies): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { HomeViewModel(deps) }
        initializer { SetupViewModel(deps) }
        initializer { ForwardsViewModel(deps) }
        initializer { LogsViewModel(deps) }
        initializer { SettingsViewModel(deps) }
        initializer { NetworkPolicyViewModel(deps) }
        initializer { ImportExportViewModel(deps) }
    }
