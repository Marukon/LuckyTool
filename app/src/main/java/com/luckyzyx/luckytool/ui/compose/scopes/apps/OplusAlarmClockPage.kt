package com.luckyzyx.luckytool.ui.compose.scopes.apps

import com.luckyzyx.luckytool.R
import com.luckyzyx.luckytool.ui.compose.scopes.ScopePageSpec
import com.luckyzyx.luckytool.utils.ModulePrefs
import com.luckyzyx.luckytool.utils.getOSVersionCode

/**
 * 闹钟页（旧 ui.fragment.scopes.apps.OplusAlarmClock 的 Compose 等价物）。
 */
object OplusAlarmClockPage {

    val spec = ScopePageSpec(
        pageKey = "oplus_alarm_clock",
        prefsName = ModulePrefs,
        packName = "com.coloros.alarmclock",
        scopes = arrayOf("com.coloros.alarmclock"),
        restartEnabled = true,
    ) {
        val c = requireNotNull(context) { "ScopeScreen 未注入 Context" }
        // ColorOS 17（OS 版本码 40）起系统已自带同类开关，模块此项不再生效：保留显示但置灰；读不到版本时视为不支持，保持可用
        val systemSupported = getOSVersionCode >= 40
        list(
            key = "alarmclock_widget_redone_mode",
            title = c.getString(R.string.alarmclock_widget_redone_mode),
            entries = c.resources.getStringArray(
                R.array.statusbar_control_center_clock_red_one_mode_entries
            ),
            entryValues = arrayOf("0", "1", "2"),
            default = "0",
            summary = if (systemSupported) {
                c.getString(R.string.alarmclock_widget_redone_mode_system_supported)
            } else {
                c.getString(R.string.current_mode) + ": %s"
            },
            enabled = !systemSupported,
            notify = true,
        )
    }
}
