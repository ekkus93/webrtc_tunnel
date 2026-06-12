package com.phillipchin.webrtctunnel.viewmodel

import com.phillipchin.webrtctunnel.data.AppDependencies

class AppViewModelFactory(private val deps: AppDependencies) {
    fun home() = HomeViewModel(deps)

    fun setup() = SetupViewModel(deps)

    fun forwards() = ForwardsViewModel(deps)

    fun logs() = LogsViewModel(deps)

    fun settings() = SettingsViewModel(deps)

    fun networkPolicy() = NetworkPolicyViewModel(deps)

    fun importExport() = ImportExportViewModel(deps)
}
