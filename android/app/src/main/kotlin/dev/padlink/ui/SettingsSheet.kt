// SettingsSheet.kt: bottom sheet with Mouse, Pen, Connection, and Display settings tabs.
package dev.padlink.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.padlink.R
import dev.padlink.settings.BtMode
import dev.padlink.settings.MouseButtonId
import dev.padlink.settings.PenAspect
import dev.padlink.settings.PenMode
import dev.padlink.settings.PressureCurve
import dev.padlink.settings.Settings
import dev.padlink.settings.SettingsStore
import dev.padlink.settings.SideButtonAction
import kotlin.math.roundToInt

/** Modal settings sheet; [initialTab] 0 Mouse, 1 Pen, 2 Connection, 3 Display. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: Settings, store: SettingsStore, initialTab: Int = 0, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            TabRow(selectedTabIndex = tab) {
                listOf(R.string.tab_mouse, R.string.tab_pen, R.string.tab_connection, R.string.tab_display)
                    .forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
                    }
            }
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> MouseTab(settings, store)
                    1 -> PenTab(settings, store)
                    2 -> ConnectionTab(settings, store)
                    else -> DisplayTab(settings, store)
                }
            }
        }
    }
}

/** Pointer, scroll, click, and layout settings. */
@Composable
private fun MouseTab(settings: Settings, store: SettingsStore) {
    val m = settings.mouse
    LabeledSlider(R.string.setting_pointer_speed, m.pointerSpeed, 0..100) { store.putInt(SettingsStore.KEY_POINTER_SPEED, it) }
    LabeledSlider(R.string.setting_scroll_speed, m.scrollSpeed, 0..100) { store.putInt(SettingsStore.KEY_SCROLL_SPEED, it) }
    SwitchRow(R.string.setting_invert_scroll, m.invertScroll) { store.putBoolean(SettingsStore.KEY_INVERT_SCROLL, it) }
    SwitchRow(R.string.setting_touch_click, m.touchClicks) { store.putBoolean(SettingsStore.KEY_TOUCH_CLICK, it) }
    HorizontalDivider()
    ChipSet(
        R.string.setting_scroll_bar,
        listOf("left" to R.string.option_left, "right" to R.string.option_right),
        buildSet {
            if (m.scrollBarLeft) add("left")
            if (m.scrollBarRight) add("right")
        },
    ) { store.putStringSet(SettingsStore.KEY_SHOW_SCROLL_BAR, it) }
    ChipSet(
        R.string.setting_button_position,
        listOf("top" to R.string.option_top, "bottom" to R.string.option_bottom),
        buildSet {
            if (m.buttonsTop) add("top")
            if (m.buttonsBottom) add("bottom")
        },
    ) { store.putStringSet(SettingsStore.KEY_SHOW_MOUSE_BUTTONS, it) }
    ChipSet(
        R.string.setting_visible_buttons,
        listOf(
            MouseButtonId.LEFT.key to R.string.button_left,
            MouseButtonId.MIDDLE.key to R.string.button_middle,
            MouseButtonId.RIGHT.key to R.string.button_right,
        ),
        m.visibleButtons.map { it.key }.toSet(),
    ) { store.putStringSet(SettingsStore.KEY_VISIBLE_MOUSE_BUTTONS, it) }
}

/** S Pen mode, tablet mapping, pressure, and side-button settings. */
@Composable
private fun PenTab(settings: Settings, store: SettingsStore) {
    val p = settings.pen
    ChoiceRow(
        R.string.setting_pen_mode,
        listOf(PenMode.TABLET to R.string.pen_mode_tablet, PenMode.POINTER to R.string.pen_mode_pointer),
        p.mode,
    ) { store.putString(SettingsStore.KEY_PEN_MODE, it.key) }
    Text(
        stringResource(if (p.mode == PenMode.TABLET) R.string.pen_mode_tablet_help else R.string.pen_mode_pointer_help),
        style = MaterialTheme.typography.bodySmall,
    )
    if (p.mode == PenMode.TABLET) {
        ChoiceRow(
            R.string.setting_pen_aspect,
            listOf(
                PenAspect.AUTO to R.string.aspect_auto,
                PenAspect.WIDE_16_9 to R.string.aspect_16_9,
                PenAspect.WIDE_16_10 to R.string.aspect_16_10,
                PenAspect.RATIO_3_2 to R.string.aspect_3_2,
                PenAspect.ULTRAWIDE_21_9 to R.string.aspect_21_9,
                PenAspect.STRETCH to R.string.aspect_stretch,
            ),
            p.aspect,
        ) { store.putString(SettingsStore.KEY_PEN_ASPECT, it.key) }
        LabeledSlider(R.string.setting_pen_area, p.areaPercent, 50..100) { store.putInt(SettingsStore.KEY_PEN_AREA, it) }
        ChoiceRow(
            R.string.setting_pen_pressure,
            listOf(
                PressureCurve.SOFT to R.string.pressure_soft,
                PressureCurve.NORMAL to R.string.pressure_normal,
                PressureCurve.FIRM to R.string.pressure_firm,
            ),
            p.pressure,
        ) { store.putString(SettingsStore.KEY_PEN_PRESSURE, it.key) }
        ChoiceRow(
            R.string.setting_pen_side_button,
            listOf(SideButtonAction.BARREL to R.string.side_barrel, SideButtonAction.ERASER to R.string.side_eraser),
            p.sideButton,
        ) { store.putString(SettingsStore.KEY_PEN_SIDE_BUTTON, it.key) }
    }
}

/** Bluetooth stack preference and wired trust management. */
@Composable
private fun ConnectionTab(settings: Settings, store: SettingsStore) {
    ChoiceRow(
        R.string.setting_bt_mode,
        listOf(BtMode.AUTO to R.string.bt_mode_auto, BtMode.CLASSIC to R.string.bt_mode_classic, BtMode.BLE to R.string.bt_mode_ble),
        settings.connection.btMode,
    ) { store.putString(SettingsStore.KEY_BT_MODE, it.key) }
    Text(stringResource(R.string.bt_mode_help), style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text(stringResource(R.string.wired_help_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.wired_help_body), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { store.putStringSet(SettingsStore.KEY_APPROVED_HELPERS, emptySet()) }) {
        Text(stringResource(R.string.action_forget_helpers))
    }
}

/** Vibration and screen settings. */
@Composable
private fun DisplayTab(settings: Settings, store: SettingsStore) {
    SwitchRow(R.string.setting_haptics, settings.mouse.haptics) { store.putBoolean(SettingsStore.KEY_HAPTICS, it) }
    SwitchRow(R.string.setting_keep_screen_on, settings.mouse.keepScreenOn) {
        store.putBoolean(SettingsStore.KEY_KEEP_SCREEN_ON, it)
    }
}

/** Integer slider with a label and the current value. */
@Composable
private fun LabeledSlider(@StringRes label: Int, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> v.roundToInt().coerceIn(range).let { if (it != value) onChange(it) } },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Label with a trailing switch. */
@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(@StringRes label: Int, options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(stringResource(text)) })
            }
        }
    }
}

/** Multi-choice chips persisted as a string set. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSet(@StringRes label: Int, options: List<Pair<String, Int>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((key, text) in options) {
                val on = key in selected
                FilterChip(
                    selected = on,
                    onClick = { onChange(if (on) selected - key else selected + key) },
                    label = { Text(stringResource(text)) },
                )
            }
        }
    }
}
