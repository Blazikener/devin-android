package com.blazikener.devin.ui.navigation

import androidx.navigation.NavHostController
import com.blazikener.devin.data.SessionFilter

/**
 * Home stays at the bottom. Save Sessions with an inclusive pop so the saved stack is mapped
 * only to Sessions, never to Home. Always consume that same snapshot before applying a shortcut.
 */
fun NavHostController.showHomeRoot() {
    if (currentDestination?.route == Routes.Home) return
    popBackStack(Routes.Sessions, inclusive = true, saveState = true)
}

fun NavHostController.showSessionsRoot(filter: SessionFilter? = null, search: Boolean = false) {
    navigate(Routes.sessions()) {
        launchSingleTop = true
        restoreState = true
    }
    if (filter != null || search) {
        currentBackStackEntry?.savedStateHandle?.set(
            SessionNavigationState.SessionsRequest, SessionsRequest(filter, search).encode(),
        )
    }
}

fun NavHostController.openSessionFromLaunch(id: String) {
    if (currentDestination?.route == Routes.Session &&
        currentBackStackEntry?.arguments?.getString("id") == id) return
    navigate(Routes.session(id))
}

fun NavHostController.openCreateFromLaunch(prompt: String?) {
    // A new entry must initialize from the shared prompt, not reuse another form's remembered state.
    navigate(Routes.create(prompt.orEmpty()))
}

/** Recovery is a root switch, not a new Sessions entry above the unresolved Create form. */
fun NavHostController.showSessionsFromCreateRecovery() {
    if (!popBackStack(Routes.Sessions, inclusive = false)) {
        popBackStack(Routes.Home, inclusive = false)
        showSessionsRoot()
    }
}

fun NavHostController.clearProtectedNavigation() {
    clearBackStack(Routes.Sessions)
    navigate(Routes.Login) { popUpTo(graph.id) { inclusive = true } }
}
