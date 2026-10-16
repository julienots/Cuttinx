package com.superessence.cuttinx.data

import com.superessence.cuttinx.core.effects.EffectPreset
import com.superessence.cuttinx.core.effects.Presets
import com.superessence.cuttinx.core.util.Ids
import com.superessence.cuttinx.core.model.EffectInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** User-saved effect presets (save, rename, duplicate, delete, import, export). */
class UserPresets(private val file: File) {
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<EffectPreset>> = _list

    private fun load(): List<EffectPreset> = runCatching { Presets.import(file.readText()) }.getOrDefault(emptyList())
    private fun persist(l: List<EffectPreset>) { file.parentFile?.mkdirs(); file.writeText(Presets.export(l)); _list.value = l }

    fun save(name: String, effects: List<EffectInstance>) = persist(_list.value + EffectPreset(Ids.new("preset"), name, effects.map { it.copy(id = Ids.new("fx")) }, category = "Mes préréglages"))
    fun rename(id: String, name: String) = persist(_list.value.map { if (it.id == id) it.copy(name = name) else it })
    fun duplicate(id: String) = _list.value.firstOrNull { it.id == id }?.let { persist(_list.value + it.copy(id = Ids.new("preset"), name = it.name + " (copie)")) }
    fun delete(id: String) = persist(_list.value.filterNot { it.id == id })
    fun importText(text: String): Int { val l = Presets.import(text); persist(_list.value + l); return l.size }
    fun exportText(ids: Collection<String>? = null) = Presets.export(_list.value.filter { ids == null || it.id in ids })
}
