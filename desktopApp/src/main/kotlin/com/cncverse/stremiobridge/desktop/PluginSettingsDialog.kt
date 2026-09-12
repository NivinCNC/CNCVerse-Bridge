package com.cncverse.stremiobridge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cncverse.stremiobridge.settings.PluginSettingSchema
import com.cncverse.stremiobridge.settings.PluginSettingsSchemaRegistry
import com.cncverse.stremiobridge.settings.SettingsPresentation
import com.cncverse.stremiobridge.ui.AmoledCard
import com.cncverse.stremiobridge.ui.AmoledCard2
import com.cncverse.stremiobridge.ui.AmoledSurface
import com.cncverse.stremiobridge.ui.CardBorder
import com.cncverse.stremiobridge.ui.DividerColor
import com.cncverse.stremiobridge.ui.TextMuted
import com.cncverse.stremiobridge.ui.TextPrimary
import com.cncverse.stremiobridge.ui.TextSecondary
import com.cncverse.stremiobridge.ui.Violet200
import com.cncverse.stremiobridge.ui.Violet400
import com.cncverse.stremiobridge.ui.Violet500
import com.cncverse.stremiobridge.ui.VioletGlow
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Desktop plugin settings dialog (the gear button). Plugins declare settings in
 * code and read them through CloudStreamApp/DataStore; every key they touch is
 * registered in [PluginSettingsSchemaRegistry], which this dialog renders as a
 * categorized UI (mirrors the cs3-desktop-client gear dialog): toggles for
 * booleans/provider switches, checkbox grids for provider lists, text/number
 * fields for the rest. Writes go back through CloudStreamApp.setKey using the
 * schema's storageKey so the plugin reads them.
 */
@Composable
fun PluginSettingsDialog(
    pluginInternalName: String,
    pluginDisplayName: String,
    onDismiss: () -> Unit,
) {
    val schemaUpdates by PluginSettingsSchemaRegistry.schemaUpdates.collectAsState()

    val settings = remember(pluginInternalName, pluginDisplayName, schemaUpdates) {
        PluginSettingsSchemaRegistry.getSettingsForPlugin(pluginInternalName, pluginDisplayName)
            .sortedWith(SettingsPresentation.settingsComparator)
    }

    // Current raw values from the shared settings store (everything is stringified)
    val currentValues = remember(pluginInternalName, schemaUpdates) {
        mutableStateMapOf<String, String?>().also { map ->
            settings.forEach { schema ->
                map[schema.storageKey] = CloudStreamApp.getKey<String>(schema.storageKey)
            }
        }
    }

    var hasChanged by remember { mutableStateOf(false) }

    fun writeValue(schema: PluginSettingSchema, raw: String?) {
        currentValues[schema.storageKey] = raw
        hasChanged = true
        CloudStreamApp.setKey(schema.storageKey, raw)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = AmoledCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.width(550.dp).fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AmoledSurface)
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = Violet400,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "$pluginDisplayName Settings",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            if (settings.isEmpty()) "No settings discovered yet"
                            else "Configure sub-providers, accounts, and scraper channels",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(AmoledCard2),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                HorizontalDivider(color = DividerColor)

                // Content
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (hasChanged) {
                            item {
                                Surface(
                                    color = VioletGlow,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "✓ Changes saved. Close this settings box to apply the new " +
                                            "provider configuration in real-time.",
                                        color = Violet200,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(14.dp),
                                    )
                                }
                            }
                        }

                        if (settings.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "This plugin has not exposed any configurable options yet. " +
                                            "Settings appear here automatically once the plugin reads them.",
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        } else {
                            val grouped = settings.groupBy { SettingsPresentation.getCategory(it.key) }
                            val sortedCategories = grouped.keys.sortedBy { category ->
                                SettingsPresentation.categoryPriorities[category] ?: 4
                            }

                            sortedCategories.forEach { category ->
                                item {
                                    Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                                        Text(
                                            category.uppercase(),
                                            color = Violet400,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        HorizontalDivider(
                                            color = Violet500.copy(alpha = 0.15f),
                                            thickness = 2.dp,
                                        )
                                    }
                                }

                                items(grouped[category]!!, key = { it.key }) { schema ->
                                    SettingCard(
                                        schema = schema,
                                        rawValue = currentValues[schema.storageKey],
                                        onChanged = { raw -> writeValue(schema, raw) },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = DividerColor)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AmoledSurface)
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Violet500,
                            contentColor = TextPrimary,
                        ),
                    ) {
                        Text("Apply & Close", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingCard(
    schema: PluginSettingSchema,
    rawValue: String?,
    onChanged: (String?) -> Unit,
) {
    val friendly = SettingsPresentation.getFriendlyName(schema.key)
    val desc = SettingsPresentation.getDescription(schema.key)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AmoledCard2)
            .border(1.dp, CardBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        when {
            schema.options != null -> ListSetting(schema, friendly, desc, rawValue, onChanged)
            schema.type == "StringSet" -> StringSetSetting(schema, friendly, desc, rawValue, onChanged)
            SettingsPresentation.isBooleanLike(schema, rawValue) -> BooleanSetting(friendly, desc, schema, rawValue, onChanged)
            else -> TextSetting(schema, friendly, desc, rawValue, onChanged)
        }
    }
}

@Composable
private fun ListSetting(
    schema: PluginSettingSchema,
    friendly: String,
    desc: String,
    rawValue: String?,
    onChanged: (String?) -> Unit,
) {
    val options = schema.options ?: emptyMap()
    val currentValue = rawValue ?: schema.defaultValue?.toString() ?: options.values.firstOrNull() ?: ""

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(friendly, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (desc.isNotEmpty()) {
            Text(desc, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            options.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (value == currentValue),
                        onClick = { onChanged(value) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Violet500,
                            unselectedColor = TextSecondary
                        )
                    )
                    Text(
                        text = label,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanSetting(
    friendly: String,
    desc: String,
    schema: PluginSettingSchema,
    rawValue: String?,
    onChanged: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(friendly, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (desc.isNotEmpty()) {
                Text(desc, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Switch(
            checked = rawValue == "true" ||
                (rawValue == null && (schema.defaultValue == true || schema.defaultValue == "true")),
            onCheckedChange = { checked -> onChanged(if (checked) "true" else "false") },
        )
    }
}

@Composable
private fun TextSetting(
    schema: PluginSettingSchema,
    friendly: String,
    desc: String,
    rawValue: String?,
    onChanged: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(friendly, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (desc.isNotEmpty()) {
            Text(
                desc,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        val isNumeric = schema.type in setOf("Int", "Long", "Float")
        OutlinedTextField(
            value = rawValue ?: schema.defaultValue?.toString() ?: "",
            onValueChange = { newValue ->
                val valid = !isNumeric || newValue.isEmpty() ||
                    (if (schema.type == "Float") newValue.toFloatOrNull() != null else newValue.toLongOrNull() != null)
                if (valid) onChanged(newValue.ifEmpty { null })
            },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet400,
                unfocusedBorderColor = CardBorder,
                cursorColor = Violet400,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Provider/source lists render as a checkbox grid. The plugin's default set
 * (usually the full provider list) provides the options; "disabled" keys store
 * the OFF providers, everything else stores the ON ones. Stored newline-joined
 * to round-trip with the SharedPreferences stub's getStringSet.
 */
@Composable
private fun StringSetSetting(
    schema: PluginSettingSchema,
    friendly: String,
    desc: String,
    rawValue: String?,
    onChanged: (String?) -> Unit,
) {
    val isDisabledKey = SettingsPresentation.isDisabledStyleKey(schema)

    val currentSet = SettingsPresentation.parseStoredSet(rawValue)
    val options = SettingsPresentation.stringSetOptions(schema, rawValue)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(friendly, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (desc.isNotEmpty()) {
            Text(
                desc,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        fun writeSet(next: Set<String>) {
            onChanged(next.joinToString("\n").ifEmpty { null })
        }

        if (options.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        writeSet(if (isDisabledKey) emptySet() else options.toSet())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isDisabledKey) "Enable All" else "Select All", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        writeSet(if (isDisabledKey) options.toSet() else emptySet())
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isDisabledKey) "Disable All" else "Deselect All", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            options.chunked(2).forEach { rowSources ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    rowSources.forEach { source ->
                        val isChecked = if (isDisabledKey) !currentSet.contains(source) else currentSet.contains(source)
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val next = if (isDisabledKey) {
                                        if (checked) currentSet - source else currentSet + source
                                    } else {
                                        if (checked) currentSet + source else currentSet - source
                                    }
                                    writeSet(next)
                                },
                            )
                            Text(
                                source.replace("API", "").replace("Api", ""),
                                color = TextPrimary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (rowSources.size < 2) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            // No known provider list yet — fall back to comma-separated editing
            OutlinedTextField(
                value = currentSet.joinToString(", "),
                onValueChange = { newValue ->
                    onChanged(
                        newValue.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                            .ifEmpty { null },
                    )
                },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet400,
                    unfocusedBorderColor = CardBorder,
                    cursorColor = Violet400,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Value-rendering helpers (categorization/friendly names live in
//    shared SettingsPresentation, shared with the web admin settings API) ──
