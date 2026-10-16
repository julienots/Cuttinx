package com.superessence.cuttinx.core.timeline

import com.superessence.cuttinx.core.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Result of an edit attempt, surfaced to the UI. */
sealed interface EditResult {
    data object Ok : EditResult
    data class Failed(val message: String) : EditResult
}

/**
 * Holds the project being edited, its history and change notifications. All UI actions go
 * through [edit] so that every change is undoable and triggers autosave.
 */
class EditSession(initial: Project, private val clock: () -> Long = System::currentTimeMillis) {
    private val _project = MutableStateFlow(initial)
    val project: StateFlow<Project> = _project.asStateFlow()
    val history = History()
    private val _revision = MutableStateFlow(0L)
    /** Incremented on every change (used by autosave and preview rebuilds). */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val current: Project get() = _project.value

    fun edit(label: String, coalesceKey: String? = null, op: (Project) -> Project): EditResult {
        val before = _project.value
        return try {
            val after = op(before)
            if (after == before) return EditResult.Ok
            history.record(before, label, coalesceKey, clock())
            publish(after.copy(modifiedAtMs = clock()))
            EditResult.Ok
        } catch (e: EditException) {
            EditResult.Failed(e.message ?: "Opération impossible")
        } catch (e: IllegalArgumentException) {
            EditResult.Failed(e.message ?: "Paramètre invalide")
        }
    }

    fun undo(): String? = history.undo(_project.value)?.let { (p, label) -> publish(p); label }
    fun redo(): String? = history.redo(_project.value)?.let { (p, label) -> publish(p); label }
    fun revertTo(index: Int) { history.revertTo(_project.value, index)?.let { publish(it) } }

    /** Replaces the project without history (e.g. loading, metadata refresh). */
    fun replace(p: Project) = publish(p)

    private fun publish(p: Project) {
        _project.value = p
        _revision.value = _revision.value + 1
    }
}
