package com.superessence.cuttinx.core.timeline

import com.superessence.cuttinx.core.model.Project

/** One entry of the edit history (shown in the "Historique" panel). */
data class HistoryEntry(val label: String, val project: Project, val timeMs: Long)

/**
 * Snapshot based undo/redo. Projects are immutable so snapshots share structure and are cheap.
 * Consecutive edits with the same [coalesceKey] (e.g. dragging a slider) are merged.
 */
class History(private val maxEntries: Int = 300) {
    private val undo = ArrayDeque<HistoryEntry>()
    private val redo = ArrayDeque<HistoryEntry>()
    private var lastKey: String? = null
    private var lastTime = 0L

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val entries: List<HistoryEntry> get() = undo.toList()
    val redoEntries: List<HistoryEntry> get() = redo.toList()

    /** Records [before] as the state preceding an edit called [label]. */
    fun record(before: Project, label: String, coalesceKey: String? = null, nowMs: Long = System.currentTimeMillis()) {
        if (coalesceKey != null && coalesceKey == lastKey && nowMs - lastTime < 1500 && undo.isNotEmpty()) {
            lastTime = nowMs
            redo.clear()
            return
        }
        undo.addLast(HistoryEntry(label, before, nowMs))
        while (undo.size > maxEntries) undo.removeFirst()
        redo.clear()
        lastKey = coalesceKey
        lastTime = nowMs
    }

    fun undo(current: Project): Pair<Project, String>? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(HistoryEntry(e.label, current, System.currentTimeMillis()))
        lastKey = null
        return e.project to e.label
    }

    fun redo(current: Project): Pair<Project, String>? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(HistoryEntry(e.label, current, System.currentTimeMillis()))
        lastKey = null
        return e.project to e.label
    }

    /** Jumps back to history entry [index] (0 = oldest); later states become redoable. */
    fun revertTo(current: Project, index: Int): Project? {
        if (index !in undo.indices) return null
        var cur = current
        while (undo.size > index) { cur = undo(cur)?.first ?: break }
        return cur
    }

    fun clear() { undo.clear(); redo.clear(); lastKey = null }
}
