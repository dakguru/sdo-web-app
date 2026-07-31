package com.karursdo.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The currently signed-in user. Null when nobody is logged in (app locked). */
data class SessionUser(val username: String, val displayName: String, val role: String) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN
}

@Singleton
class SessionManager @Inject constructor() {
    private val _current = MutableStateFlow<SessionUser?>(null)
    val current: StateFlow<SessionUser?> = _current

    fun setCurrent(user: SessionUser) { _current.value = user }
    fun clear() { _current.value = null }

    /** Display name for stamping notes/activity, or null if nobody is signed in. */
    fun authorName(): String? = _current.value?.displayName ?: _current.value?.username
}
