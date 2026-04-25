package com.combat.nomm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

object ErrorNotifications {
    data class ErrorToast(
        val id: Long,
        val title: String,
        val detail: String,
    )

    private val _notifications: MutableStateFlow<List<ErrorToast>> = MutableStateFlow(emptyList())
    val notifications: StateFlow<List<ErrorToast>> = _notifications

    private val counter = AtomicLong(0)

    fun push(title: String, detail: String) {
        val toast = ErrorToast(counter.incrementAndGet(), title, detail)
        _notifications.update { it + toast }
    }

    fun dismiss(id: Long) {
        _notifications.update { list -> list.filter { it.id != id } }
    }
}
