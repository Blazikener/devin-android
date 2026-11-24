package com.blazikener.devin.ui.navigation

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blazikener.devin.data.SessionFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real Navigation save/restore and argument identity without rendering screen content. */
@RunWith(AndroidJUnit4::class)
class NavigationActionsTest {
    @Test fun restoredListConsumesLatestSnapshotAndAppliesShortcutSeparately() = onMain {
        val nav = controller()
        nav.showSessionsRoot()
        nav.currentBackStackEntry!!.savedStateHandle["query"] = "first"
        val marker = ViewModelProvider(nav.currentBackStackEntry!!)[Marker::class.java]
        nav.showHomeRoot()
        assertEquals(Routes.Home, nav.currentDestination!!.route)
        nav.showSessionsRoot(SessionFilter.NeedsYou, search = true)
        val restored = nav.currentBackStackEntry!!
        assertSame(marker, ViewModelProvider(restored)[Marker::class.java])
        assertEquals("first", restored.savedStateHandle.get<String>("query"))
        assertEquals(SessionsRequest(SessionFilter.NeedsYou, true),
            SessionsRequest.decode(restored.savedStateHandle[SessionNavigationState.SessionsRequest]))
        restored.savedStateHandle[SessionNavigationState.SessionsRequest] = null
        restored.savedStateHandle["query"] = "latest"
        nav.showHomeRoot()
        nav.showSessionsRoot()
        assertEquals("latest", nav.currentBackStackEntry!!.savedStateHandle.get<String>("query"))
        assertTrue(nav.popBackStack())
        assertEquals(Routes.Home, nav.currentDestination!!.route)
    }

    @Test fun signOutDestroysSavedProtectedViewModelsAndState() = onMain {
        val nav = controller()
        nav.showSessionsRoot()
        val marker = ViewModelProvider(nav.currentBackStackEntry!!)[Marker::class.java]
        nav.currentBackStackEntry!!.savedStateHandle["query"] = "account A private query"
        nav.showHomeRoot()
        nav.navigate(Routes.Settings)
        nav.clearProtectedNavigation()
        assertEquals(Routes.Login, nav.currentDestination!!.route)
        assertTrue(marker.cleared)
        nav.navigate(Routes.Home) { popUpTo(Routes.Login) { inclusive = true } }
        nav.showSessionsRoot()
        assertNotSame(marker, ViewModelProvider(nav.currentBackStackEntry!!)[Marker::class.java])
        assertNull(nav.currentBackStackEntry!!.savedStateHandle.get<String>("query"))
    }

    @Test fun warmSessionLaunchPushesDifferentIdentityButReusesExactTopSession() = onMain {
        val nav = controller()
        nav.openSessionFromLaunch("session-A")
        nav.openSessionFromLaunch("session-B")
        val targetEntry = nav.currentBackStackEntry!!.id
        nav.openSessionFromLaunch("session-B")
        assertEquals(targetEntry, nav.currentBackStackEntry!!.id)
        assertTrue(nav.popBackStack())
        assertEquals("session-A", nav.currentBackStackEntry!!.arguments!!.getString("id"))
        assertTrue(nav.popBackStack())
        assertEquals(Routes.Home, nav.currentDestination!!.route)
    }

    @Test fun recoveredCreateRetainsOneShotHomeCleanupThroughProcessRestoration() = onMain {
        val nav = controller()
        nav.openCreatedSessionFromLaunch("saved-success", "submitted-prompt-fingerprint")
        val restored = controller(nav.saveState())
        assertEquals("saved-success", restored.currentBackStackEntry!!.arguments!!.getString("id"))
        assertTrue(restored.popBackStack())
        val home = restored.currentBackStackEntry!!
        assertEquals(Routes.Home, home.destination.route)
        assertEquals("submitted-prompt-fingerprint", home.savedStateHandle.get<String>(SessionNavigationState.ConsumedHomeCreate))
        home.savedStateHandle[SessionNavigationState.ConsumedHomeCreate] = null
        assertNull(home.savedStateHandle.get<String>(SessionNavigationState.ConsumedHomeCreate))
    }

    @Test fun sharedPromptCreatesFreshEntryAndBackRestoresOriginalForm() = onMain {
        val nav = controller()
        nav.openCreateFromLaunch("original")
        assertEquals(true, nav.currentBackStackEntry!!.savedStateHandle.get<Boolean>(SessionNavigationState.IncomingCreate))
        val originalEntry = nav.currentBackStackEntry!!.id
        nav.currentBackStackEntry!!.savedStateHandle["draft"] = "unsent"
        nav.openCreateFromLaunch("fresh shared text /?&")
        assertNotEquals(originalEntry, nav.currentBackStackEntry!!.id)
        assertEquals("fresh shared text /?&", nav.currentBackStackEntry!!.arguments!!.getString("prompt"))
        assertTrue(nav.popBackStack())
        assertEquals(originalEntry, nav.currentBackStackEntry!!.id)
        assertEquals("unsent", nav.currentBackStackEntry!!.savedStateHandle.get<String>("draft"))
        val restored = controller(nav.saveState())
        assertEquals(true, restored.currentBackStackEntry!!.savedStateHandle.get<Boolean>(SessionNavigationState.IncomingCreate))
    }

    @Test fun settingsAndColdLaunchKeepTheirExactParents() = onMain {
        val nav = controller()
        nav.openSessionFromLaunch("cold")
        assertTrue(nav.popBackStack())
        assertEquals(Routes.Home, nav.currentDestination!!.route)
        nav.showSessionsRoot()
        nav.navigate(Routes.Settings)
        nav.openSessionFromLaunch("warm")
        assertTrue(nav.popBackStack())
        assertEquals(Routes.Settings, nav.currentDestination!!.route)
        assertTrue(nav.popBackStack())
        assertEquals(Routes.Sessions, nav.currentDestination!!.route)
    }

    @Test fun recoverySessionsHasHomeAsRootAndPreservesAnExistingList() = onMain {
        val nav = controller()
        nav.openCreateFromLaunch("uncertain Home task")
        nav.showSessionsFromCreateRecovery()
        assertEquals(Routes.Sessions, nav.currentDestination!!.route)
        assertEquals(SessionsRequest(SessionFilter.All, recoverCreate = true),
            SessionsRequest.decode(nav.currentBackStackEntry!!.savedStateHandle[SessionNavigationState.SessionsRequest]))
        nav.showHomeRoot()
        assertEquals(Routes.Home, nav.currentDestination!!.route)
        nav.showSessionsRoot()
        nav.currentBackStackEntry!!.savedStateHandle["query"] = "preserved"
        val existing = nav.currentBackStackEntry!!.id
        nav.openCreateFromLaunch("uncertain Sessions task")
        nav.showSessionsFromCreateRecovery()
        assertEquals(existing, nav.currentBackStackEntry!!.id)
        assertEquals("preserved", nav.currentBackStackEntry!!.savedStateHandle.get<String>("query"))
        nav.showHomeRoot()
        assertEquals(Routes.Home, nav.currentDestination!!.route)
    }

    class Marker : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private fun onMain(test: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(test)
    }

    private fun controller(saved: Bundle? = null): NavHostController {
        val nav = NavHostController(ApplicationProvider.getApplicationContext())
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        nav.setLifecycleOwner(owner)
        nav.setViewModelStore(ViewModelStore())
        val navigator = ImmediateNavigator()
        nav.navigatorProvider.addNavigator(navigator)
        saved?.let(nav::restoreState)
        nav.graph = nav.createGraph(startDestination = Routes.Home) {
            listOf(Routes.Home, Routes.Login, Routes.Sessions, Routes.Settings, Routes.Session, Routes.Create).forEach { path ->
                addDestination(navigator.createDestination().apply {
                    route = path
                    if (path == Routes.Sessions) {
                        addArgument("filter", NavArgument.Builder().setDefaultValue("").build())
                        addArgument("search", NavArgument.Builder().setDefaultValue(false).build())
                    }
                    if (path == Routes.Create) addArgument("prompt", NavArgument.Builder().setDefaultValue("").build())
                })
            }
        }
        return nav
    }

    @Navigator.Name("immediate")
    private class ImmediateNavigator : Navigator<NavDestination>() {
        override fun createDestination() = NavDestination(this)
        override fun navigate(entries: List<NavBackStackEntry>, navOptions: NavOptions?, navigatorExtras: Extras?) {
            entries.forEach { state.push(it) }
        }
        override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) { state.pop(popUpTo, savedState) }
    }
}
