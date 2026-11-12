package home.phonelock.parent

import androidx.compose.runtime.Composable

/**
 * Recovery access: turn USB and wireless debugging on for a while on the child's device, for repairs or
 * updates over adb when its own screens can't be used. [onPick] gets the minutes, 0 to turn it off now.
 */
@Composable
internal fun DebuggingDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    OptionsDialog("Turn on debugging?", onDismiss,
        note = "For repairs: turns on USB and wireless debugging on $name, so it can be fixed or updated from a " +
            "computer even if its screens aren't working. It turns off again by itself.\n\nWhile it's on, anyone " +
            "with a paired computer can control the device. Recent events will show where to connect.",
        options = listOf(30L, 60L, 120L).map { m -> MenuEntry("On for ${fmtMinutes(m)}") { onPick(m) } } +
            MenuEntry("Turn it off now") { onPick(0) })
}
