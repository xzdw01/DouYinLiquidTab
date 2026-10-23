package io.github.xzdw.dylt.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.xzdw.dylt.config.ManagerSettings
import io.github.xzdw.dylt.framework.ModuleActivation

@Composable
fun ManagerRoot(settings: ManagerSettings, activation: ModuleActivation, actions: UiActions) {
    var selectedPageName by rememberSaveable { mutableStateOf(ManagerPage.HOME.name) }
    val selectedPage = ManagerPage.entries.firstOrNull { it.name == selectedPageName } ?: ManagerPage.HOME
    val onSelected: (ManagerPage) -> Unit = { selectedPageName = it.name }

    GlassManager(settings, activation, selectedPage, onSelected, actions)
}
