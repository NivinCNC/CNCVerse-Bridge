package com.cncverse.stremiobridge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cncverse.stremiobridge.shadowui.ShadowDialog
import com.cncverse.stremiobridge.shadowui.ShadowUi
import com.cncverse.stremiobridge.ui.*

/**
 * Desktop-native rendering of plugin settings UIs captured by ShadowUi.
 *
 * The plugin's own Android UI code ran against recording stubs; this renderer
 * walks the live stub tree and draws it with Compose. Interactions dispatch
 * the plugin's real listeners through [ShadowUi.dispatch] — the plugin reacts,
 * mutates the stub objects, and the UI re-renders. This is how CineStream /
 * StreamPlay's full settings screens run on desktop.
 */
@Composable
fun ShadowUiHost() {
    val dialogs by ShadowUi.dialogs.collectAsState()
    val version by ShadowUi.version.collectAsState()
    val toast by ShadowUi.toast.collectAsState()

    // Render the top dialog
    dialogs.lastOrNull()?.let { dialog ->
        key(version, dialog.id) {
            ShadowDialogFrame(dialog)
        }
    }

    // Transient toast overlay
    toast?.let { event ->
        val visible = remember(event.at) { mutableStateOf(true) }
        LaunchedEffect(event.at) {
            kotlinx.coroutines.delay(2200)
            visible.value = false
        }
        if (visible.value) {
            Box(Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter).padding(bottom = 28.dp)) {
                Surface(
                    color = Color(0xE6161032),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet500.copy(alpha = 0.35f)),
                ) {
                    Text(
                        event.text,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

// ── Dialog frame ────────────────────────────────────────────────────────────

@Composable
private fun ShadowDialogFrame(shadow: ShadowDialog) {
    Dialog(
        onDismissRequest = {
            val platform = shadow.platform
            ShadowUi.dispatch("dialog-cancel") {
                when (platform) {
                    is android.app.Dialog -> platform.cancel()
                    is androidx.appcompat.app.AlertDialog -> platform.cancel()
                    is androidx.fragment.app.Fragment -> platform.dismiss()
                }
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = AmoledCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.width(620.dp).heightIn(max = 800.dp),
        ) {
            when (val platform = shadow.platform) {
                is androidx.fragment.app.Fragment -> FragmentBody(shadow, platform)
                is androidx.appcompat.app.AlertDialog -> AppCompatDialogBody(platform, shadow.view)
                is android.app.Dialog -> PlatformDialogBody(platform, platform.contentView ?: shadow.view)
                else -> shadow.view?.let { Column(Modifier.verticalScroll(rememberScrollState())) { ShadowNode(it, 1) } }
            }
        }
    }
}

@Composable
private fun FragmentBody(shadow: ShadowDialog, fragment: androidx.fragment.app.Fragment) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
        ) {
            IconButton(
                onClick = { ShadowUi.dispatch("fragment-dismiss") { fragment.dismiss() } },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(AmoledCard2),
            ) {
                Icon(Icons.Filled.Close, "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 700.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 18.dp)
        ) {
            shadow.view?.let { ShadowNode(it, 1) }
        }
    }
}

@Composable
private fun PlatformDialogBody(dialog: android.app.Dialog, view: android.view.View?) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        dialog.title?.let {
            Text(
                it.toString(),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        dialog.message?.let {
            Text(
                it.toString(),
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            view?.let {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ShadowNode(it, 1)
                }
            }
        }

        dialog.items?.let { items ->
            Column {
                items.forEachIndexed { index, label ->
                    val selected = dialog.isSingleChoice && dialog.checkedItem == index
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                ShadowUi.dispatch("dialog-item") {
                                    if (dialog.isSingleChoice) {
                                        dialog.checkedItem = index
                                        dialog.itemsListener?.onClick(dialog, index)
                                    } else if (dialog.isMultiChoice) {
                                        dialog.multiChoiceListener?.onClick(dialog, index, true)
                                    } else {
                                        dialog.itemsListener?.onClick(dialog, index)
                                        dialog.dismiss()
                                    }
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                    ) {
                        if (dialog.isSingleChoice) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        } else if (dialog.isMultiChoice) {
                            Checkbox(
                                checked = dialog.checkedItems?.getOrNull(index) == true,
                                onCheckedChange = null,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
                        Text(label.toString(), color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }

        ShadowDialogButtons(
            positiveText = dialog.positiveText,
            negativeText = dialog.negativeText,
            neutralText = dialog.neutralText,
            onPositive = { ShadowUi.dispatch("dialog-positive") { dialog.positiveListener?.onClick(dialog, -1) } },
            onNegative = { ShadowUi.dispatch("dialog-negative") { dialog.negativeListener?.onClick(dialog, -2) } },
            onNeutral = { ShadowUi.dispatch("dialog-neutral") { dialog.neutralListener?.onClick(dialog, -3) } },
        )
    }
}

@Composable
private fun AppCompatDialogBody(dialog: androidx.appcompat.app.AlertDialog, view: android.view.View?) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        dialog.title?.let {
            Text(it.toString(), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        dialog.message?.let {
            Text(it.toString(), color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            view?.let {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ShadowNode(it, 1)
                }
            }
        }
        dialog.items?.let { items ->
            Column {
                items.forEachIndexed { index, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                ShadowUi.dispatch("dialog-item") {
                                    if (dialog.isSingleChoice) {
                                        dialog.checkedItem = index
                                        dialog.itemsListener?.onClick(dialog, index)
                                    } else if (dialog.isMultiChoice) {
                                        dialog.multiChoiceListener?.onClick(dialog, index, true)
                                    } else {
                                        dialog.itemsListener?.onClick(dialog, index)
                                        dialog.dismiss()
                                    }
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                    ) {
                        if (dialog.isSingleChoice) {
                            RadioButton(selected = dialog.checkedItem == index, onClick = null, modifier = Modifier.padding(end = 12.dp))
                        }
                        Text(label.toString(), color = TextPrimary, fontSize = 14.sp)
                    }
                }
            }
        }
        ShadowDialogButtons(
            positiveText = dialog.positiveText,
            negativeText = dialog.negativeText,
            neutralText = dialog.neutralText,
            onPositive = { ShadowUi.dispatch("dialog-positive") { dialog.positiveListener?.onClick(dialog, -1) } },
            onNegative = { ShadowUi.dispatch("dialog-negative") { dialog.negativeListener?.onClick(dialog, -2) } },
            onNeutral = { ShadowUi.dispatch("dialog-neutral") { dialog.neutralListener?.onClick(dialog, -3) } },
        )
    }
}

@Composable
private fun ShadowDialogButtons(
    positiveText: CharSequence?,
    negativeText: CharSequence?,
    neutralText: CharSequence?,
    onPositive: () -> Unit,
    onNegative: () -> Unit,
    onNeutral: () -> Unit,
) {
    if (positiveText == null && negativeText == null && neutralText == null) return
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        neutralText?.let {
            TextButton(onClick = onNeutral) { Text(it.toString(), color = TextSecondary) }
            Spacer(Modifier.width(8.dp))
        }
        negativeText?.let {
            OutlinedButton(
                onClick = onNegative,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            ) { Text(it.toString(), color = TextSecondary) }
            Spacer(Modifier.width(10.dp))
        }
        positiveText?.let {
            Button(
                onClick = onPositive,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet500, contentColor = TextPrimary),
            ) { Text(it.toString(), fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ── View tree renderer ──────────────────────────────────────────────────────

/** px (Android, density 2) → dp */
private fun Int.pxToDp() = (this / 2f).dp

/** px (Android, density 2) → dp (float values like corner radii) */
private fun Float.pxToDp() = (this / 2f).dp

/** ARGB int → Compose color */
private fun Int.toColor() = Color(this)

@Composable
private fun ShadowNode(view: android.view.View, depth: Int) {
    if (view.visibility == android.view.View.GONE) return
    if (depth > 24) return

    val verticalMargin = ((view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: 0).pxToDp() +
        ((view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0).pxToDp()
    val horizontalMargin = ((view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.leftMargin ?: 0).pxToDp() +
        ((view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.rightMargin ?: 0).pxToDp()

    Box(Modifier.padding(horizontal = horizontalMargin / 2, vertical = verticalMargin / 2)) {
        when (view) {
            is androidx.recyclerview.widget.RecyclerView -> RecyclerViewNode(view)
            is android.widget.ListView -> ListNode(view)
            is android.widget.ScrollView -> ScrollNode(view)
            is android.widget.LinearLayout -> LinearLayoutNode(view, depth)
            is android.widget.RadioGroup -> LinearLayoutNode(view, depth)
            is android.widget.RelativeLayout -> ColumnNode(view, depth)
            is android.widget.FrameLayout -> FrameNode(view, depth)
            is android.widget.EditText -> EditTextNode(view)
            is android.widget.Switch -> SwitchNode(view)
            is android.widget.CheckBox -> CheckBoxNode(view)
            is android.widget.RadioButton -> RadioButtonNode(view)
            is android.widget.Button -> ButtonNode(view)
            is android.widget.ImageButton -> IconButtonNode(view)
            is android.widget.ImageView -> ImageNode(view)
            is android.widget.ProgressBar -> ProgressNode(view)
            is android.widget.TextView -> TextNode(view)
            is android.widget.Space -> Spacer(Modifier.height(((view.layoutParams?.height ?: 16) / 2f).dp.coerceAtLeast(4.dp)))
            is android.view.ViewGroup -> ColumnNode(view, depth)
            else -> PlainViewNode(view)
        }
    }
}

@Composable
private fun ChildrenColumn(group: android.view.ViewGroup, depth: Int, spaced: Boolean = true) {
    Column(
        verticalArrangement = if (spaced) Arrangement.spacedBy(2.dp) else Arrangement.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        group.children.forEach { child ->
            ShadowNode(child, depth + 1)
        }
    }
}

@Composable
private fun LinearLayoutNode(layout: android.widget.LinearLayout, depth: Int) {
    if (layout.orientation == android.widget.LinearLayout.HORIZONTAL) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            layout.children.forEach { child ->
                Box(Modifier.weight(1f, fill = false)) { ShadowNode(child, depth + 1) }
            }
            if (layout.children.isEmpty()) Spacer(Modifier.width(8.dp))
        }
    } else {
        ChildrenColumn(layout, depth)
    }
}

@Composable
private fun ColumnNode(group: android.view.ViewGroup, depth: Int) {
    ChildrenColumn(group, depth)
}

@Composable
private fun FrameNode(group: android.view.ViewGroup, depth: Int) {
    Box(Modifier.fillMaxWidth()) {
        group.children.lastOrNull()?.let { ShadowNode(it, depth + 1) }
    }
}

@Composable
private fun ScrollNode(scroll: android.widget.ScrollView) {
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
        scroll.children.forEach { ShadowNode(it, 2) }
    }
}

@Composable
private fun ListNode(list: android.widget.ListView) {
    val adapter = list.adapter
    Column(Modifier.fillMaxWidth()) {
        if (adapter != null) {
            repeat(adapter.getCount()) { position ->
                val row = runCatching { adapter.getView(position, null, list) }.getOrNull()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { ShadowUi.dispatch("list-item") { list.performItemClick(position) } }
                        .padding(vertical = 6.dp),
                ) {
                    row?.let { ShadowNode(it, 3) }
                }
            }
        }
    }
}

@Composable
private fun RecyclerViewNode(recycler: androidx.recyclerview.widget.RecyclerView) {
    val adapter = recycler.adapter ?: return
    Column(Modifier.fillMaxWidth()) {
        repeat(adapter.getItemCount()) { position ->
            val holder = runCatching { adapter.onCreateViewHolder(recycler, adapter.getItemViewType(position)) }.getOrNull()
            if (holder != null) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (adapter as androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>)
                        .onBindViewHolder(holder, position)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { ShadowUi.dispatch("rv-item") { holder.itemView.performClick() } },
                ) {
                    ShadowNode(holder.itemView, 3)
                }
            }
        }
    }
}

@Composable
private fun TextViewStyle(text: String, view: android.widget.TextView) {
    val textColor = view.textColor.toColor()
    Text(
        text,
        color = textColor,
        fontSize = (view.textSizePx / 2f).coerceIn(9f, 34f).sp,
        fontWeight = if (view.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (view.italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = if (view.gravity and 0x7 == 0x1) TextAlign.Center else null,
        maxLines = view.maxLines,
        overflow = TextOverflow.Ellipsis,
        letterSpacing = view.letterSpacing.sp * 0.06f,
        modifier = Modifier.padding(
            start = view.paddingLeft.pxToDp(),
            top = view.paddingTop.pxToDp(),
            end = view.paddingRight.pxToDp(),
            bottom = view.paddingBottom.pxToDp(),
        ),
    )
}

@Composable
private fun TextNode(view: android.widget.TextView) {
    val text = view.text.toString().let { if (view.allCaps) it.uppercase() else it }
    val bg = view.background
    val corner = (bg as? android.graphics.drawable.GradientDrawable)?.cornerRadius ?: 0f

    if (view.clickListener != null) {
        val shape = RoundedCornerShape(corner.coerceAtLeast(8f).pxToDp() * 2)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable { ShadowUi.dispatch("text-click") { view.performClick() } }
                .backgroundModifier(bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            TextViewStyle(text, view)
        }
    } else if (bg is android.graphics.drawable.ColorDrawable || bg is android.graphics.drawable.GradientDrawable) {
        Box(
            Modifier
                .fillMaxWidth()
                .backgroundModifier(bg),
        ) {
            TextViewStyle(text, view)
        }
    } else {
        TextViewStyle(text, view)
    }
}

@Composable
private fun ButtonNode(view: android.widget.Button) {
    Button(
        onClick = { ShadowUi.dispatch("button-click") { view.performClick() } },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = (view.background as? android.graphics.drawable.ColorDrawable)?.color?.toColor() ?: Violet500,
            contentColor = view.textColor.toColor(),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = view.paddingLeft.pxToDp(),
                top = view.paddingTop.pxToDp(),
                end = view.paddingRight.pxToDp(),
                bottom = view.paddingBottom.pxToDp(),
            ),
    ) {
        Text(
            view.text.toString().let { if (view.allCaps) it.uppercase() else it },
            fontSize = (view.textSizePx / 2f).coerceIn(10f, 20f).sp,
            fontWeight = if (view.bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun EditTextNode(view: android.widget.EditText) {
    val isPassword = (view.inputType and 0x81) == 0x81 || (view.inputType and 128) == 128 ||
        (view.inputType and 144) == 144
    val isNumber = (view.inputType and 2) == 2 || (view.inputType and 3) == 3
    Column(Modifier.fillMaxWidth()) {
        if (view.hint.isNotEmpty() && view.text.isEmpty()) {
            Text(view.hint.toString(), color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        OutlinedTextField(
            value = view.text.toString(),
            onValueChange = { newValue ->
                ShadowUi.dispatch("edit-text") { view.programmaticText(newValue) }
            },
            singleLine = view.singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextPrimary,
                fontSize = (view.textSizePx / 2f).coerceIn(10f, 16f).sp,
            ),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet400,
                unfocusedBorderColor = CardBorder,
                cursorColor = Violet400,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SwitchNode(view: android.widget.Switch) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { ShadowUi.dispatch("switch-toggle") { view.toggle() } }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (view.text.isNotEmpty()) {
                TextViewStyle(view.text.toString(), view)
            }
        }
        Spacer(Modifier.width(14.dp))
        Switch(
            checked = view.isChecked,
            onCheckedChange = { checked ->
                ShadowUi.dispatch("switch-change") { view.isChecked = checked }
            },
        )
    }
}

@Composable
private fun CheckBoxNode(view: android.widget.CheckBox) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { ShadowUi.dispatch("checkbox-toggle") { view.toggle() } }
            .padding(vertical = 4.dp),
    ) {
        Checkbox(
            checked = view.isChecked,
            onCheckedChange = { checked ->
                ShadowUi.dispatch("checkbox-change") { view.isChecked = checked }
            },
        )
        Text(
            view.text.toString(),
            color = view.textColor.toColor(),
            fontSize = (view.textSizePx / 2f).coerceIn(10f, 16f).sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun RadioButtonNode(view: android.widget.RadioButton) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { ShadowUi.dispatch("radio-toggle") { view.isChecked = true } }
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = view.isChecked, onClick = null)
        Text(
            view.text.toString(),
            color = view.textColor.toColor(),
            fontSize = (view.textSizePx / 2f).coerceIn(10f, 16f).sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ImageNode(view: android.widget.ImageView) {
    val named = view.imageDrawable as? android.graphics.drawable.NamedDrawable
    val icon = when (named?.name?.removeSuffix(".xml")) {
        "settings_icon" -> Icons.Filled.Settings
        "save_icon" -> Icons.Filled.Save
        "delete_icon" -> Icons.Filled.Delete
        "add_icon" -> Icons.Filled.Add
        "language_icon", "language" -> Icons.Filled.Language
        "link_icon" -> Icons.Filled.Link
        "play_icon" -> Icons.Filled.PlayArrow
        "speed_icon" -> Icons.Filled.Speed
        "star_icon" -> Icons.Filled.Star
        "tune_icon" -> Icons.Filled.Tune
        "key_icon", "vpn_icon" -> Icons.Filled.VpnKey
        else -> null
    }
    if (icon != null) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(VioletGlow)
                .border(1.dp, Violet500.copy(alpha = 0.25f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = named?.name, tint = Violet400, modifier = Modifier.size(20.dp))
        }
    } else {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(AmoledCard2)
                .border(1.dp, CardBorder.copy(alpha = 0.6f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IconButtonNode(view: android.widget.ImageButton) {
    IconButton(
        onClick = { ShadowUi.dispatch("icon-button") { view.performClick() } },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AmoledCard2),
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Violet400,
        )
    }
}

@Composable
private fun ProgressNode(view: android.widget.ProgressBar) {
    if (view.max > 0) {
        LinearProgressIndicator(
            progress = { view.progress.toFloat() / view.max },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Violet500,
        )
    } else {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Violet500)
    }
}

@Composable
private fun PlainViewNode(view: android.view.View) {
    val bg = view.background
    if (bg is android.graphics.drawable.ColorDrawable) {
        val height = ((view.layoutParams?.height ?: 1) / 2f).dp.coerceAtLeast(1.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(1.dp))
                .background(bg.color.toColor()),
        )
    } else if (bg is android.graphics.drawable.GradientDrawable) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = ((view.layoutParams?.height ?: 2) / 2f).dp.coerceAtLeast(1.dp))
                .backgroundModifier(bg),
        )
    } else {
        Spacer(Modifier.height(4.dp))
    }
}

/** Background from recorded drawables (color / gradient / state-list). */
@Composable
private fun Modifier.backgroundModifier(bg: android.graphics.drawable.Drawable?): Modifier {
    if (bg == null) return this
    return when (bg) {
        is android.graphics.drawable.ColorDrawable -> this.background(bg.color.toColor())
        is android.graphics.drawable.GradientDrawable -> {
            val corner = bg.cornerRadius.coerceAtLeast(0f).pxToDp() * 2
            val shape = RoundedCornerShape(corner)
            val base = (bg.gradientColors?.firstOrNull() ?: bg.color).toColor()
            val withBorder = if (bg.strokeWidth > 0) {
                this.border((bg.strokeWidth / 2f).dp, bg.strokeColor.toColor(), shape)
            } else this
            withBorder.background(base, shape)
        }
        is android.graphics.drawable.StateListDrawable -> {
            val inner = bg.firstDrawable
            if (inner != null) backgroundModifier(inner) else this
        }
        else -> this
    }
}
