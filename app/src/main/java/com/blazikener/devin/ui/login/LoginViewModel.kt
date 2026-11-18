package com.blazikener.devin.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blazikener.devin.AppGraph
import com.blazikener.devin.data.Account
import com.blazikener.devin.data.AuthStore
import com.blazikener.devin.data.DevinApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUi(
    val key: String = "",
    val showKey: Boolean = false,
    val advanced: Boolean = false,
    val baseUrl: String = AuthStore.DEFAULT_BASE_URL,
    val orgId: String = "",
    val needOrg: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
)

/** Holds sign-in form state and the in-flight key check so both survive rotation. */
class LoginViewModel(private val g: AppGraph) : ViewModel() {
    private val _ui = MutableStateFlow(LoginUi())
    val ui: StateFlow<LoginUi> = _ui

    fun setKey(v: String) = _ui.update { it.copy(key = v, error = null) }
    fun setBaseUrl(v: String) = _ui.update { it.copy(baseUrl = v, error = null) }
    fun setOrgId(v: String) = _ui.update { it.copy(orgId = v, error = null) }
    fun toggleShowKey() = _ui.update { it.copy(showKey = !it.showKey) }
    fun toggleAdvanced() = _ui.update { it.copy(advanced = !it.advanced) }

    fun submit() {
        val s = _ui.value
        if (s.busy) return
        val k = s.key.trim()
        if (k.isEmpty()) { _ui.update { it.copy(error = "Paste your API key") }; return }
        val base = s.baseUrl.trim().trimEnd('/')
        val org = s.orgId.trim().ifEmpty { null }
        baseUrlProblem(base)?.let { msg -> _ui.update { it.copy(advanced = true, error = msg) }; return }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val probe = DevinApi({ base }, { k }, { org })
            runCatching { probe.self(k) }
                .onSuccess { self ->
                    val effective = org ?: self.effectiveOrgId
                    if (effective == null) {
                        _ui.update { it.copy(busy = false, needOrg = true, error = "This key isn't tied to one organization. Enter the org ID (org-…).") }
                    } else {
                        g.auth.save(Account(k, effective, self.displayName, self.userId, base))
                        _ui.update { it.copy(busy = false, signedIn = true) }
                    }
                }
                .onFailure { e -> _ui.update { it.copy(busy = false, error = signInErrorMessage(e, base)) } }
        }
    }
}
