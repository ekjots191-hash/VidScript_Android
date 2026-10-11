package com.roboek.vidscript.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.DrawScope

object C {
    val bg = Color(0xFF0B0C12); val card = Color(0xFF12131B); val field = Color(0xFF181A24)
    val fieldHover = Color(0xFF1F2130); val border = Color(0xFF262838); val text = Color(0xFFE9E9ED)
    val muted = Color(0xFF9397AB); val faint = Color(0xFF75798C); val accent = Color(0xFF9184D9)
    val accentText = Color(0xFFD2CEFD); val accentTint = Color(0xFF2B2741); val accentDeep = Color(0xFF423A6A)
    val track = Color(0xFF1E2030); val rec = Color(0xFFE0646C); val recTint = Color(0xFF2A171B)
    val disabled = Color(0xFF595D6C); val ok = Color(0xFF6CCB8E)
}

@Composable
fun VidScriptTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = C.accent, onPrimary = Color(0xFF14102B), background = C.bg, onBackground = C.text,
        surface = C.card, onSurface = C.text, surfaceVariant = C.field, onSurfaceVariant = C.muted,
        outline = C.border, error = C.rec,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Marchio: onda che diventa righe di testo (stesso disegno dell'app Windows). */
@Composable
fun Logo(size: Dp, tile: Boolean = true, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 100f
        val tileBg = Color(0xFF14151F)
        if (tile) {
            drawRoundRect(tileBg, Offset(k, k), Size(98 * k, 98 * k), androidx.compose.ui.geometry.CornerRadius(23 * k))
            drawRoundRect(Color(0xFF2A2D3E), Offset(k, k), Size(98 * k, 98 * k),
                androidx.compose.ui.geometry.CornerRadius(23 * k), style = Stroke(k))
        }
        val sw = 6 * k
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color) =
            drawLine(c, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), strokeWidth = sw, cap = StrokeCap.Round)
        listOf(Triple(22f, 42f, 58f), Triple(32f, 30f, 70f), Triple(42f, 38f, 62f), Triple(52f, 46f, 54f))
            .forEach { (x, a, b) -> line(x, a, x, b, C.accent) }
        line(62f, 38f, 78f, 38f, C.text)
        line(62f, 50f, 74f, 50f, Color(0xFFA9AAAF))
        line(62f, 62f, 78f, 62f, Color(0xFF74747C))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(C.card)
            .border(1.dp, C.border, RoundedCornerShape(16.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = modifier.padding(bottom = 6.dp))

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    Button(
        onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) C.rec else C.accent,
            contentColor = if (danger) Color.White else Color(0xFF14102B),
            disabledContainerColor = C.track, disabledContentColor = C.disabled),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick, modifier.heightIn(min = 44.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, C.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.accentText, disabledContentColor = C.disabled),
    ) { Text(text, fontSize = 14.sp) }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, sub: String? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(
            checkedColor = C.accent, uncheckedColor = C.faint, checkmarkColor = Color(0xFF14102B)))
        Column(Modifier.padding(start = 6.dp)) {
            Text(text, color = if (enabled) C.text else C.disabled, fontSize = 15.sp)
            if (sub != null) Text(sub, color = C.faint, fontSize = 12.sp)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.field).padding(3.dp)) {
        options.forEachIndexed { i, t ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) C.accentDeep else Color.Transparent)
                    .clickable(enabled) { onSelect(i) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(t, color = if (sel) C.text else C.muted, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
fun Dropdown(value: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
             enabled: Boolean = true, empty: String = "—") {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.field)
                .border(1.dp, C.border, RoundedCornerShape(12.dp))
                .clickable(enabled && options.isNotEmpty()) { expanded = true }.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = if (options.isEmpty()) empty else value
            val tint = if (enabled && options.isNotEmpty()) C.text else C.disabled
            Text(shown, color = tint, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("▾", color = C.muted)
        }
        DropdownMenu(expanded, { expanded = false }, containerColor = C.field) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o, color = C.text) }, onClick = { expanded = false; onSelect(o) })
            }
        }
    }
}

@Composable
fun Dots(level: Int, color: Color = C.accent) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..5) Box(Modifier.size(9.dp).clip(CircleShape).background(if (i <= level) color else C.track))
    }
}

@Composable
fun Badge(text: String) {
    Text(text, color = C.accentText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.accentTint).padding(horizontal = 9.dp, vertical = 3.dp))
}
