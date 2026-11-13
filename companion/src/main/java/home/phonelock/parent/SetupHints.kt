package home.phonelock.parent

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The main screen's setup prompts: one card for each permission this phone still needs. */
@SuppressLint("BatteryLife")
@Composable
internal fun SetupHints() {
    PermissionHint("Allow background use so requests arrive right away.",
        granted = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        settings = { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) })
    // Android 14 and later ask for this separately: without it an emergency alert is only a notification,
    // not a screen that wakes the phone (the alarm still sounds).
    // Only the GitHub build asks for it (Play restricts full-screen alerts to calling and alarm apps).
    if (Build.VERSION.SDK_INT >= 34 && BuildConfig.FLAVOR == "github") {
        PermissionHint("Allow emergency alerts to show full screen, like an incoming call.",
            granted = { it.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() },
            settings = { Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${it.packageName}")) })
    }
}

/**
 * A setup prompt: [text] and an Allow button opening [settings], shown while [granted] is false. Re-checked
 * whenever the app comes back to the front, e.g. after the system screen.
 */
@Composable
private fun PermissionHint(text: String, granted: (Context) -> Boolean, settings: (Context) -> Intent) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(granted(ctx)) }
    LifecycleResumeEffect(Unit) {
        ok = granted(ctx)
        onPauseOrDispose {}
    }
    if (ok) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text)
            TextButton(onClick = { runCatching { ctx.startActivity(settings(ctx)) } }) { Text("Allow") }
        }
    }
}
