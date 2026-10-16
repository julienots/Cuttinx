package com.superessence.cuttinx.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.cuttinx.ui.theme.Cx
import com.superessence.cuttinx.ui.theme.CuttinxDisplay
import com.superessence.cuttinx.ui.theme.LocalCx
import kotlin.math.roundToInt

/** The original CUTTINX mark: two blades crossing with a cut gap (drawn, no bitmap). */
@Composable
fun CuttinxMark(size: Dp, progress: Float = 1f, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 108f * s, y / 108f * s)
        val k = progress.coerceIn(0f, 1f)
        val slide = (1 - k) * s * 0.4f
        val white = Path().apply { val a = p(33f, 30f); val b = p(45f, 30f); val c = p(75f, 78f); val d = p(63f, 78f); moveTo(a.x - slide, a.y - slide); lineTo(b.x - slide, b.y - slide); lineTo(c.x - slide, c.y - slide); lineTo(d.x - slide, d.y - slide); close() }
        drawPath(white, Color.White)
        val y1 = Path().apply { val a = p(63f, 30f); val b = p(75f, 30f); val c = p(58.5f, 56.4f); val d = p(52.5f, 46.8f); moveTo(a.x + slide, a.y - slide); lineTo(b.x + slide, b.y - slide); lineTo(c.x + slide, c.y - slide); lineTo(d.x + slide, d.y - slide); close() }
        val y2 = Path().apply { val a = p(49.5f, 61.2f); val b = p(45f, 78f); val c = p(33f, 78f); val d = p(46.5f, 56.4f); moveTo(a.x + slide, a.y + slide); lineTo(b.x + slide, b.y + slide); lineTo(c.x + slide, c.y + slide); lineTo(d.x + slide, d.y + slide); close() }
        drawPath(y1, Cx.Yellow); drawPath(y2, Cx.Yellow)
        if (k > 0.85f) drawCircle(Cx.Yellow, s * 4f / 108f * ((k - 0.85f) / 0.15f), p(78f, 74f))
    }
}

/** "CUTT" white + "INX." yellow wordmark. */
@Composable
fun CuttinxWordmark(fontSize: TextUnit = 34.sp, modifier: Modifier = Modifier) {
    val accent = Cx.Yellow
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = if (LocalCx.current.dark) Color.White else Color(0xFF111111))) { append("CUTT") }
        withStyle(SpanStyle(color = accent)) { append("INX.") }
    }, fontFamily = CuttinxDisplay, fontWeight = FontWeight.ExtraBold, fontSize = fontSize, modifier = modifier, letterSpacing = 1.sp)
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val cx = LocalCx.current
    Button(onClick, modifier, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = cx.accent, contentColor = cx.onAccent), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Vertical icon + label button used in the editor toolbars. */
@Composable
fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, badge: String? = null) {
    val cx = LocalCx.current
    val tint = when { !enabled -> cx.dim.copy(alpha = 0.4f); active -> cx.accent; else -> MaterialTheme.colorScheme.onSurface }
    Column(
        modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
            if (badge != null) Text(badge, fontSize = 8.sp, color = Color.Black, modifier = Modifier.align(Alignment.TopEnd).offset(8.dp, (-4).dp).background(cx.accent, CircleShape).padding(horizontal = 3.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.5.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Labelled slider with value read-out, reset and fine +/- steps. */
@Composable
fun ValueSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    modifier: Modifier = Modifier, default: Float? = null, unit: String = "", decimals: Int = 2, step: Float? = null,
    onKeyframe: (() -> Unit)? = null, hasKeyframe: Boolean = false,
) {
    val cx = LocalCx.current
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            if (onKeyframe != null) {
                IconButton(onKeyframe, Modifier.size(28.dp)) { Icon(Icons.Default.Diamond, "Image clé", tint = if (hasKeyframe) cx.accent else cx.dim, modifier = Modifier.size(16.dp)) }
            }
            val fine = step ?: ((range.endInclusive - range.start) / 100f)
            IconButton({ onChange((value - fine).coerceIn(range)) }, Modifier.size(28.dp)) { Icon(Icons.Default.Remove, "Moins", Modifier.size(14.dp)) }
            Text(format(value, decimals) + unit, style = MaterialTheme.typography.labelMedium, color = cx.accent, modifier = Modifier.widthIn(min = 44.dp), textAlign = TextAlign.Center)
            IconButton({ onChange((value + fine).coerceIn(range)) }, Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Plus", Modifier.size(14.dp)) }
            if (default != null) IconButton({ onChange(default) }, Modifier.size(28.dp), enabled = value != default) { Icon(Icons.Default.Refresh, "Réinitialiser", Modifier.size(14.dp)) }
        }
        Slider(value.coerceIn(range), onChange, valueRange = range, modifier = Modifier.height(30.dp),
            colors = SliderDefaults.colors(thumbColor = cx.accent, activeTrackColor = cx.accent, inactiveTrackColor = cx.line))
    }
}

fun format(v: Float, decimals: Int): String = if (decimals == 0) v.roundToInt().toString() else "%.${decimals}f".format(v)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalCx.current.dim, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val cx = LocalCx.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Text(label(o), modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) cx.accent else cx.panel2).clickable { onSelect(o) }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (sel) cx.onAccent else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    val cx = LocalCx.current
    Row(modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = cx.dim)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = cx.onAccent, checkedTrackColor = cx.accent))
    }
}

/** Colour picker: swatches + hue/saturation/brightness/alpha sliders. Colour is 0xAARRGGBB. */
@Composable
fun ColorPicker(color: Long, onChange: (Long) -> Unit, withAlpha: Boolean = true) {
    val swatches = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFCC1F, 0xFFFF3D3D, 0xFFFF3D8B, 0xFF9B5CFF, 0xFF29A3FF, 0xFF29D3FF, 0xFF2ECC71, 0xFF9CFF3D, 0xFFFF8A1F, 0xFF8B5A2B, 0xFF7F8C8D)
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) }
    val alpha = ((color ushr 24) and 0xFF) / 255f
    fun build(h: Float, s: Float, v: Float, a: Float): Long = (android.graphics.Color.HSVToColor((a * 255).roundToInt(), floatArrayOf(h, s, v)).toLong() and 0xFFFFFFFFL)
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { sw ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(sw.toInt())).border(2.dp, if ((sw and 0xFFFFFF) == (color and 0xFFFFFF)) Cx.Yellow else Color.Gray.copy(alpha = 0.4f), CircleShape)
                    .clickable { onChange((sw and 0xFFFFFF) or ((alpha * 255).roundToInt().toLong() shl 24)) })
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })))
        ValueSlider("Teinte", hsv[0], 0f..360f, { onChange(build(it, hsv[1], hsv[2], alpha)) }, decimals = 0, unit = "°")
        ValueSlider("Saturation", hsv[1], 0f..1f, { onChange(build(hsv[0], it, hsv[2], alpha)) })
        ValueSlider("Luminosité", hsv[2], 0f..1f, { onChange(build(hsv[0], hsv[1], it, alpha)) })
        if (withAlpha) ValueSlider("Opacité", alpha, 0f..1f, { onChange(build(hsv[0], hsv[1], hsv[2], it)) })
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, singleLine: Boolean = true, confirm: String = "OK", onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = singleLine, modifier = Modifier.fillMaxWidth(), minLines = if (singleLine) 1 else 3) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit, danger: Boolean = false) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onConfirm) { Text(confirm, color = if (danger) Cx.Danger else LocalCx.current.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler") } })
}

@Composable
fun NumberField(label: String, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    var t by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(t, { s -> t = s.filter { it.isDigit() }.take(5); t.toIntOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

/** Small info card used for tips and honest limitations. */
@Composable
fun InfoCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Default.Info, tint: Color = LocalCx.current.accent) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)).padding(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProgressCard(title: String, progress: Float?, detail: String? = null, onCancel: (() -> Unit)? = null) {
    val cx = LocalCx.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cx.panel2).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (onCancel != null) TextButton(onCancel) { Text("Annuler") }
        }
        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cx.accent)
        else LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = cx.accent)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = cx.dim, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Loading spinner used by the splash. */
@Composable
fun PulsingDot(color: Color = Cx.Yellow) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = a)))
}
