package io.github.lyzio.ajnav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors
import java.util.Locale

/** Rounded card with a small caps title, used by Settings and Operators & Prices. */
@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        color = AjColors.Card,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AjColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title.uppercase(), color = AjColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** A row of mutually exclusive choices (e.g. Auto / Dark / Light). */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Surface(
                color = if (on) AjColors.Pill else AjColors.Land,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (on) AjColors.Teal else AjColors.Border),
                modifier = Modifier.weight(1f).clickable { onSelect(value) },
            ) {
                Text(
                    label,
                    color = if (on) AjColors.Ice else AjColors.Body,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Number field that saves as you type. Blank saves null when [allowEmpty]. The text is kept
 * locally (keyed by [key]) so saving doesn't reformat what the user is typing.
 */
@Composable
fun NumberField(
    key: Any,
    label: String,
    initial: Float?,
    onCommit: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
    allowEmpty: Boolean = false,
    range: ClosedFloatingPointRange<Float> = 0f..1000f,
) {
    var text by remember(key) { mutableStateOf(initial?.let { formatNumber(it, decimals) }.orEmpty()) }
    val parsed = text.replace(',', '.').toFloatOrNull()
    val valid = (text.isBlank() && allowEmpty) || (parsed != null && parsed in range)
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.trim()
            val v = text.replace(',', '.').toFloatOrNull()
            when {
                text.isBlank() && allowEmpty -> onCommit(null)
                v != null && v in range -> onCommit(v)
            }
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.isNotEmpty() && !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = fieldColors(),
        modifier = modifier,
    )
}

fun formatNumber(value: Float, decimals: Int): String =
    if (decimals == 0) value.toInt().toString() else String.format(Locale.getDefault(), "%.${decimals}f", value)

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AjColors.Land,
    unfocusedContainerColor = AjColors.Land,
    focusedBorderColor = AjColors.Teal,
    unfocusedBorderColor = AjColors.Border,
    cursorColor = AjColors.Teal,
    focusedTextColor = AjColors.Ice,
    unfocusedTextColor = AjColors.Ice,
    focusedLabelColor = AjColors.Teal,
    unfocusedLabelColor = AjColors.Muted,
)

@Composable
fun LabeledRow(title: String, subtitle: String?, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AjColors.Ice, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = AjColors.Muted, fontSize = 12.sp) }
        }
        trailing()
    }
}
