package io.github.lyzio.ajnav.operators

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lyzio.ajnav.feature.charging.planner.OperatorMode
import io.github.lyzio.ajnav.feature.navigation.ui.theme.AjColors
import io.github.lyzio.ajnav.ui.ChoiceRow
import io.github.lyzio.ajnav.ui.NumberField
import io.github.lyzio.ajnav.ui.Section
import io.github.lyzio.ajnav.ui.fieldColors
import io.github.lyzio.ajnav.ui.formatNumber

/** Operators & Prices tab: pick preferred operators and enter your own prices (kr/kWh). */
@Composable
fun OperatorsScreen(viewModel: OperatorsViewModel = hiltViewModel()) {
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(AjColors.Bg)
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Operators & Prices", color = AjColors.Ice, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (loading) {
                CircularProgressIndicator(color = AjColors.Teal, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else if (hasKey) {
                IconButton(onClick = { viewModel.refresh(force = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload operators", tint = AjColors.Muted)
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            item {
                Section("Preferred operators") {
                    val mode = settings?.planner?.operatorMode ?: OperatorMode.PREFER
                    ChoiceRow(
                        options = listOf(OperatorMode.PREFER to "Prefer these", OperatorMode.ONLY to "Only these"),
                        selected = mode,
                        onSelect = viewModel::setMode,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (mode == OperatorMode.PREFER) {
                            "Starred operators rank higher in the suggestions; others are still shown."
                        } else {
                            "Only starred operators are suggested. With no stars, all operators are used."
                        },
                        color = AjColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                Section("Prices") {
                    Text(
                        "Used in this order: your subscription price → Open Charge Map's price → your usual price for the operator → the default price below.",
                        color = AjColors.Body,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    settings?.let { s ->
                        NumberField(
                            key = "global",
                            label = "Default price (kr/kWh)",
                            initial = s.planner.prices.globalDefaultPrice,
                            onCommit = { it?.let(viewModel::setGlobalDefaultPrice) },
                            range = 0f..50f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                when {
                    !hasKey -> Notice("Add your free Open Charge Map key in Settings to load the operator list.")
                    error != null -> Notice(error!!)
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search operators (e.g. Ionity, Tesla)") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AjColors.Teal) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AjColors.Muted)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                )
            }
            if (hasKey && rows.isEmpty() && !loading) {
                item { Text("No operators found.", color = AjColors.Muted, modifier = Modifier.padding(8.dp)) }
            }
            items(rows, key = { it.id }) { row ->
                OperatorItem(
                    row = row,
                    expanded = expanded == row.id,
                    onToggleExpand = { expanded = if (expanded == row.id) null else row.id },
                    onStar = { viewModel.setPreferred(row.id, !row.preferred) },
                    onSubscription = { viewModel.setSubscriptionPrice(row.id, it) },
                    onUsual = { viewModel.setUsualPrice(row.id, it) },
                )
            }
            item {
                Text(
                    "Operator list © Open Charge Map contributors (CC BY 4.0)",
                    color = AjColors.Muted.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = AjColors.Card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, AjColors.Border)) {
        Text(text, color = AjColors.Coral, fontSize = 13.sp, modifier = Modifier.padding(12.dp).fillMaxWidth())
    }
}

@Composable
private fun OperatorItem(
    row: OperatorRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onStar: () -> Unit,
    onSubscription: (Float?) -> Unit,
    onUsual: (Float?) -> Unit,
) {
    Surface(
        color = AjColors.Card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (row.preferred) AjColors.Teal else AjColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.clickable(onClick = onToggleExpand).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onStar) {
                    Icon(
                        if (row.preferred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (row.preferred) "Remove from preferred" else "Add to preferred",
                        tint = if (row.preferred) AjColors.Coral else AjColors.Muted,
                    )
                }
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(row.name, color = AjColors.Ice, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    priceSummary(row)?.let { Text(it, color = AjColors.Teal, fontSize = 12.sp) }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide prices" else "Set prices",
                    tint = AjColors.Muted,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        key = "sub_${row.id}",
                        label = "My subscription price (kr/kWh)",
                        initial = row.subscriptionPrice,
                        onCommit = onSubscription,
                        allowEmpty = true,
                        range = 0f..50f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Always used for this operator. Leave empty if you have no subscription.", color = AjColors.Muted, fontSize = 12.sp)
                    NumberField(
                        key = "usual_${row.id}",
                        label = "Usual price (kr/kWh)",
                        initial = row.usualPrice,
                        onCommit = onUsual,
                        allowEmpty = true,
                        range = 0f..50f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Used when Open Charge Map has no price for a charger.", color = AjColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun priceSummary(row: OperatorRow): String? {
    val parts = listOfNotNull(
        row.subscriptionPrice?.let { "Subscription ${formatNumber(it, 2)} kr/kWh" },
        row.usualPrice?.let { "Usual ${formatNumber(it, 2)} kr/kWh" },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

