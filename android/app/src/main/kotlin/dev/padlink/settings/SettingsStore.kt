// SettingsStore.kt: SharedPreferences-backed settings with the original app's keys and defaults.
package dev.padlink.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the `settings` preferences file and publishes typed snapshots on every change. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    /** Latest settings snapshot. */
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Builds a snapshot from the preference file, applying contract defaults. */
    private fun read(): Settings {
        val buttonsPosition = prefs.getStringSet(KEY_SHOW_MOUSE_BUTTONS, null) ?: setOf("bottom")
        val scrollBar = prefs.getStringSet(KEY_SHOW_SCROLL_BAR, null) ?: setOf("right")
        val visible = prefs.getStringSet(KEY_VISIBLE_MOUSE_BUTTONS, null) ?: setOf("left", "right")
        return Settings(
            mouse = MouseSettings(
                pointerSpeed = prefs.getInt(KEY_POINTER_SPEED, 50).coerceIn(0, 100),
                scrollSpeed = prefs.getInt(KEY_SCROLL_SPEED, 50).coerceIn(0, 100),
                invertScroll = prefs.getBoolean(KEY_INVERT_SCROLL, false),
                touchClicks = prefs.getBoolean(KEY_TOUCH_CLICK, true),
                buttonsTop = "top" in buttonsPosition,
                buttonsBottom = "bottom" in buttonsPosition,
                visibleButtons = MouseButtonId.fromKeys(visible),
                scrollBarLeft = "left" in scrollBar,
                scrollBarRight = "right" in scrollBar,
                haptics = prefs.getBoolean(KEY_HAPTICS, true),
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            ),
            pen = PenSettings(
                mode = PenMode.fromKey(prefs.getString(KEY_PEN_MODE, null)),
                aspect = PenAspect.fromKey(prefs.getString(KEY_PEN_ASPECT, null)),
                areaPercent = prefs.getInt(KEY_PEN_AREA, 100).coerceIn(50, 100),
                pressure = PressureCurve.fromKey(prefs.getString(KEY_PEN_PRESSURE, null)),
                sideButton = SideButtonAction.fromKey(prefs.getString(KEY_PEN_SIDE_BUTTON, null)),
            ),
            connection = ConnectionSettings(
                transport = TransportKind.fromKey(prefs.getString(KEY_TRANSPORT, null)),
                btMode = BtMode.fromKey(prefs.getString(KEY_BT_MODE, null)),
                btTarget = prefs.getString(KEY_BT_TARGET, null),
            ),
            samsungHintDismissed = prefs.getBoolean(KEY_SAMSUNG_HINT_DISMISSED, false),
        )
    }

    /** Persists an integer setting. */
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    /** Persists a boolean setting. */
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    /** Persists a string setting. */
    fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    /** Persists a string-set setting. */
    fun putStringSet(key: String, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()

    /** Selects the transport and, for Bluetooth, the target host address. */
    fun selectTransport(kind: TransportKind, btTarget: String? = null) {
        val editor = prefs.edit().putString(KEY_TRANSPORT, kind.key)
        if (btTarget != null) editor.putString(KEY_BT_TARGET, btTarget)
        editor.apply()
    }

    /** Returns true if the helper token (hex) was approved for tethering links. */
    fun isHelperApproved(tokenHex: String): Boolean =
        tokenHex in (prefs.getStringSet(KEY_APPROVED_HELPERS, emptySet()) ?: emptySet())

    /** Adds a helper token (hex) to the tethering allow-list. */
    fun approveHelper(tokenHex: String) {
        val current = prefs.getStringSet(KEY_APPROVED_HELPERS, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_APPROVED_HELPERS, current + tokenHex).apply()
    }

    companion object {
        const val FILE_NAME = "settings"
        const val KEY_POINTER_SPEED = "mouse_pointer_speed"
        const val KEY_SCROLL_SPEED = "mouse_scroll_speed"
        const val KEY_INVERT_SCROLL = "mouse_invert_scroll"
        const val KEY_TOUCH_CLICK = "touch_click_enabled"
        const val KEY_SHOW_MOUSE_BUTTONS = "show_mouse_buttons"
        const val KEY_VISIBLE_MOUSE_BUTTONS = "visible_mouse_buttons"
        const val KEY_SHOW_SCROLL_BAR = "show_scroll_bar"
        const val KEY_HAPTICS = "haptic_feedback"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_TRANSPORT = "transport"
        const val KEY_BT_MODE = "bt_mode"
        const val KEY_BT_TARGET = "bt_target"
        const val KEY_PEN_MODE = "pen_mode"
        const val KEY_PEN_ASPECT = "pen_aspect"
        const val KEY_PEN_AREA = "pen_area"
        const val KEY_PEN_PRESSURE = "pen_pressure"
        const val KEY_PEN_SIDE_BUTTON = "pen_side_button"
        const val KEY_APPROVED_HELPERS = "approved_helpers"
        const val KEY_SAMSUNG_HINT_DISMISSED = "samsung_hint_dismissed"
    }
}
