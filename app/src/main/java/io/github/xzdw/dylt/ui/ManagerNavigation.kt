package io.github.xzdw.dylt.ui

import androidx.navigation3.runtime.NavKey

/** Navigation keys used by the manager's application list. */
internal sealed interface ManagerAppRoute : NavKey {
    data object List : ManagerAppRoute
    data class Detail(val targetKey: String) : ManagerAppRoute
}

/**
 * Keys for the manager's own pages. [Main] hosts the pager with the three tabs;
 * a sub-page is pushed on top of it and popped by the system back gesture.
 */
internal sealed interface ManagerRoute : NavKey {
    data object Main : ManagerRoute

    data class SettingsCategory(val page: ManagerSettingsSubpage) : ManagerRoute

    data class HomeInfo(val page: ManagerHomeSubpage) : ManagerRoute
}
