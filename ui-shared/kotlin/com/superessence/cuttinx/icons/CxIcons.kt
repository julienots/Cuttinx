package com.superessence.cuttinx.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.superessence.cuttinx.core.gfx.IconDef
import com.superessence.cuttinx.core.gfx.IconSet

/**
 * The CUTTINX icon set as Compose vectors (Android and PC): drawn for the app on a 24 × 24 grid
 * with 2 px rounded strokes. Definitions live in the core ([IconSet]) so the tutorial films use
 * exactly the same icons. Use with `Icon(CxIcons.Split, "Couper")`: the tint colours them.
 */
object CxIcons {
    private fun build(d: IconDef): ImageVector {
        val b = ImageVector.Builder(d.name, 24.dp, 24.dp, 24f, 24f)
        d.paths.forEachIndexed { i, p ->
            if (i in d.filled) b.addPath(addPathNodes(p), fill = SolidColor(Color.Black))
            else b.addPath(addPathNodes(p), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    val ZoomIn by lazy { build(IconSet.ZoomIn) }
    val ZoomOut by lazy { build(IconSet.ZoomOut) }
    val Fit by lazy { build(IconSet.Fit) }
    val Move by lazy { build(IconSet.Move) }
    val Keyboard by lazy { build(IconSet.Keyboard) }
    val Editor by lazy { build(IconSet.Editor) }
    val Twitch by lazy { build(IconSet.Twitch) }
    val Identity by lazy { build(IconSet.Identity) }
    val Help by lazy { build(IconSet.Help) }
    val Media by lazy { build(IconSet.Media) }
    val Text by lazy { build(IconSet.Text) }
    val Music by lazy { build(IconSet.Music) }
    val Sfx by lazy { build(IconSet.Sfx) }
    val Effects by lazy { build(IconSet.Effects) }
    val Transitions by lazy { build(IconSet.Transitions) }
    val Animation by lazy { build(IconSet.Animation) }
    val Voice by lazy { build(IconSet.Voice) }
    val Pro by lazy { build(IconSet.Pro) }
    val Image by lazy { build(IconSet.Image) }
    val Video by lazy { build(IconSet.Video) }
    val Upload by lazy { build(IconSet.Upload) }
    val Download by lazy { build(IconSet.Download) }
    val Play by lazy { build(IconSet.Play) }
    val Pause by lazy { build(IconSet.Pause) }
    val SkipStart by lazy { build(IconSet.SkipStart) }
    val SkipEnd by lazy { build(IconSet.SkipEnd) }
    val Prev by lazy { build(IconSet.Prev) }
    val Next by lazy { build(IconSet.Next) }
    val Replay by lazy { build(IconSet.Replay) }
    val Split by lazy { build(IconSet.Split) }
    val Delete by lazy { build(IconSet.Delete) }
    val RippleDelete by lazy { build(IconSet.RippleDelete) }
    val Duplicate by lazy { build(IconSet.Duplicate) }
    val Marker by lazy { build(IconSet.Marker) }
    val Undo by lazy { build(IconSet.Undo) }
    val Redo by lazy { build(IconSet.Redo) }
    val KeyframeAdd by lazy { build(IconSet.KeyframeAdd) }
    val KeyframeRemove by lazy { build(IconSet.KeyframeRemove) }
    val Mirror by lazy { build(IconSet.Mirror) }
    val Add by lazy { build(IconSet.Add) }
    val Close by lazy { build(IconSet.Close) }
    val Check by lazy { build(IconSet.Check) }
    val Warning by lazy { build(IconSet.Warning) }
    val Back by lazy { build(IconSet.Back) }
    val ChevronDown by lazy { build(IconSet.ChevronDown) }
    val More by lazy { build(IconSet.More) }
    val Search by lazy { build(IconSet.Search) }
    val Relink by lazy { build(IconSet.Relink) }
    val Lock by lazy { build(IconSet.Lock) }
    val Unlock by lazy { build(IconSet.Unlock) }
    val Eye by lazy { build(IconSet.Eye) }
    val EyeOff by lazy { build(IconSet.EyeOff) }
    val Volume by lazy { build(IconSet.Volume) }
    val VolumeOff by lazy { build(IconSet.VolumeOff) }
    val ZoomSlow by lazy { build(IconSet.ZoomSlow) }
    val Progress by lazy { build(IconSet.Progress) }
    val Captions by lazy { build(IconSet.Captions) }
    val Hook by lazy { build(IconSet.Hook) }
    val Watermark by lazy { build(IconSet.Watermark) }
    val NoiseOff by lazy { build(IconSet.NoiseOff) }
    val Studio by lazy { build(IconSet.Studio) }
    val Background by lazy { build(IconSet.Background) }
    val Idea by lazy { build(IconSet.Idea) }
    val Do by lazy { build(IconSet.Do) }
    val Dont by lazy { build(IconSet.Dont) }
    val Calendar by lazy { build(IconSet.Calendar) }
    val Star by lazy { build(IconSet.Star) }
    val Rocket by lazy { build(IconSet.Rocket) }
    val Folder get() = Media
}
