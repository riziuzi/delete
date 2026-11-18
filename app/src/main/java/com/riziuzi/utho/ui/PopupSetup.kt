package com.riziuzi.utho.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.riziuzi.utho.alarm.AlarmScheduler

/** Xiaomi / Redmi / POCO phones have their own extra switches for apps that pop up by themselves. */
val isXiaomi: Boolean get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

fun canOpenOverApps(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

fun ignoresBatteryLimits(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

/** Everything Android itself lets us check for the check-in pop-up to open on its own. */
fun popupReady(ctx: Context): Boolean = !needsFullScreenPermission(ctx) && canOpenOverApps(ctx)

private fun open(ctx: Context, intent: Intent, fallback: Intent? = null) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        fallback?.let { f -> runCatching { ctx.startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

/** Xiaomi's "Other permissions" page for this app (Show on lock screen, pop-up windows in background). */
fun openXiaomiPermissions(ctx: Context) = open(
    ctx,
    Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", ctx.packageName),
    appDetails(ctx),
)

/**
 * Make the check-in pop-up open by itself: a list of the phone switches it needs, each with a tick when on
 * (where Android lets us check) and a button to the right settings page, plus a test.
 */
/** Changes every time the screen comes back (e.g. from a settings page), so switches are looked at again. */
@Composable
fun rememberResumeTick(): Int {
    var refresh by remember { mutableIntStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return refresh
}

@Composable
fun PopupSetupScreen(nav: Nav) {
    val ctx = LocalContext.current
    val refresh = rememberResumeTick()
    val fullScreen = remember(refresh) { !needsFullScreenPermission(ctx) }
    val overApps = remember(refresh) { canOpenOverApps(ctx) }
    val battery = remember(refresh) { ignoresBatteryLimits(ctx) }
    var testing by remember { mutableStateOf(false) }

    ScreenScaffold(title = tr("Pop-up opens by itself", "Pop-up apne aap khule"), onBack = { nav.back() }) {
        Muted(
            tr(
                "When a study interval ends, the check-in should wake the screen and start listening without a tap. Phones only allow that once these are on.",
                "Interval khatam hone par check-in ko screen jagani chahiye aur bina tap ke sunna shuru karna chahiye. Phone yeh tabhi karne deta hai jab yeh sab on hon.",
            ),
        )
        CardBox {
            if (Build.VERSION.SDK_INT >= 34) {
                SetupRow(
                    fullScreen, tr("Full-screen pop-up", "Full-screen pop-up"),
                    tr("Shows over the lock screen and wakes it.", "Lock screen par dikhe aur screen jagaye."),
                    "setup-fullscreen",
                ) { openFullScreenSettings(ctx) }
                Divider()
            }
            SetupRow(
                overApps, tr("Appear on top", "Upar dikhe"),
                tr("Opens by itself while you're using the phone.", "Phone chalate waqt bhi apne aap khule."),
                "setup-overlay",
            ) { open(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")), appDetails(ctx)) }
            Divider()
            SetupRow(
                battery, tr("No battery limits", "Battery ki rok nahi"),
                tr("So check-ins come on time, not minutes late.", "Taaki check-in time par aaye, minute late nahi."),
                "setup-battery",
            ) {
                open(
                    ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            if (isXiaomi) {
                Divider()
                SetupRow(
                    null, tr("Xiaomi: two more switches", "Xiaomi: do aur switch"),
                    tr(
                        "In \"Other permissions\" turn on \"Show on lock screen\" and \"Display pop-up windows while running in the background\". (Xiaomi doesn't let apps check these.)",
                        "\"Other permissions\" mein \"Show on lock screen\" aur \"Display pop-up windows while running in the background\" on karein. (Xiaomi apps ko yeh check nahi karne deta.)",
                    ),
                    "setup-xiaomi",
                ) { openXiaomiPermissions(ctx) }
            }
        }
        CardBox {
            Text(tr("Test it", "Test karein"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Muted(
                if (testing) tr("Coming in 10 seconds. Lock the phone now to see it on the lock screen.", "10 second mein aayega. Abhi phone lock karein, lock screen par dikhega.")
                else tr("The pop-up comes in 10 seconds and listens, like a real check-in. Nothing is saved.", "Pop-up 10 second mein aayega aur sunega, asli check-in jaisa. Kuch save nahi hoga."),
            )
            BigButton(tr("Test the pop-up", "Pop-up test karein"), {
                AlarmScheduler.scheduleTestPopup(ctx, 10_000L)
                testing = true
            }, Modifier.testTag("testPopup"))
        }
    }
}

/** One switch: ✓ when on, ! when off, and a button to its settings page. [on] = null when the phone can't tell us. */
@Composable
private fun SetupRow(on: Boolean?, title: String, sub: String, tag: String, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val green = if (cs.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(tag)) {
        Icon(
            if (on == true) Icons.Filled.CheckCircle else Icons.Outlined.ErrorOutline, contentDescription = null,
            tint = if (on == true) green else if (on == false) cs.error else cs.onSurfaceVariant, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(sub)
        }
        if (on != true) SmallTextButton(if (on == null) tr("Open", "Kholein") else tr("Allow", "Allow karein")) { onOpen() }
    }
}
