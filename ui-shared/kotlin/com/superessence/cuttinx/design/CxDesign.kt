package com.superessence.cuttinx.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * CUTTINX design rules, shared by the phone and PC apps: tool-like controls (8 dp corners, no
 * pills, no glow), a filled accent only for the one main action of a screen, hairline borders
 * everywhere else.
 */
object CxDesign {
    /** Buttons: squared-off, like the controls of an editing desk. */
    val Button = RoundedCornerShape(8.dp)
    /** Selection chips and small tags. */
    val Chip = RoundedCornerShape(7.dp)
    /** Cards and groups of settings. */
    val Card = RoundedCornerShape(12.dp)

    /** Colours of a selectable chip: selected = tinted with a thin accent outline, never a full yellow block. */
    fun chipBg(selected: Boolean, accent: Color) = if (selected) accent.copy(alpha = 0.14f) else Color.Transparent
    fun chipBorder(selected: Boolean, accent: Color, line: Color) = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.85f) else line)
}

/** A selectable chip with the CUTTINX look (padding included). */
fun Modifier.cxChip(selected: Boolean, accent: Color, line: Color = Color.White.copy(alpha = 0.14f), onClick: () -> Unit): Modifier =
    this.clip(CxDesign.Chip).background(CxDesign.chipBg(selected, accent))
        .border(CxDesign.chipBorder(selected, accent, line), CxDesign.Chip)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)

/** Small tag (status, "Plus", "Pub"): outlined, not a filled pill. */
fun Modifier.cxTag(accent: Color): Modifier =
    this.clip(CxDesign.Chip).background(accent.copy(alpha = 0.10f)).border(1.dp, accent.copy(alpha = 0.55f), CxDesign.Chip).padding(horizontal = 8.dp, vertical = 3.dp)
