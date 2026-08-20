package com.klk.hams.ui.count

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.klk.hams.AppConfig
import com.klk.hams.data.location.GpsLockState
import com.klk.hams.push.PushUiState
import com.klk.hams.ui.theme.FieldAmber
import com.klk.hams.ui.theme.FieldEarth
import com.klk.hams.ui.theme.FieldEarthOn
import com.klk.hams.ui.theme.FieldForest
import com.klk.hams.ui.theme.FieldForestOn
import com.klk.hams.ui.theme.FieldHairline
import com.klk.hams.ui.theme.FieldInk
import com.klk.hams.ui.theme.FieldInkSoft
import com.klk.hams.ui.theme.FieldScarlet
import com.klk.hams.ui.theme.FieldSlate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface LocationGateState {
    data object CheckingPermission : LocationGateState
    data object RequestingPermission : LocationGateState
    data object PermissionDenied : LocationGateState
    data object CheckingLocationServices : LocationGateState
    data object LocationServicesOff : LocationGateState
    data object Ready : LocationGateState
}

@Composable
fun CountScreen(
    vm: CountViewModel,
    onExitRequired: () -> Unit,
    onGatePassed: () -> Unit = {},
    onNotificationGrantRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    var gateState by remember {
        mutableStateOf<LocationGateState>(LocationGateState.CheckingPermission)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gateState =
            if (granted) LocationGateState.CheckingLocationServices
            else LocationGateState.PermissionDenied
    }

    LaunchedEffect(gateState) {
        when (gateState) {
            LocationGateState.CheckingPermission -> {
                gateState =
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) LocationGateState.CheckingLocationServices
                    else LocationGateState.RequestingPermission
            }
            LocationGateState.RequestingPermission -> {
                permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            LocationGateState.CheckingLocationServices -> {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                gateState = if (enabled) LocationGateState.Ready else LocationGateState.LocationServicesOff
            }
            else -> Unit
        }
    }

    when (gateState) {
        LocationGateState.PermissionDenied -> BlockingMessage(
            text = "GPS permission is required to record cuts.\nEnable location and reopen the app.",
            onDismiss = onExitRequired
        )
        LocationGateState.LocationServicesOff -> BlockingMessage(
            text = "Turn on GPS / location services to use HAMS.",
            onDismiss = onExitRequired
        )
        LocationGateState.CheckingPermission,
        LocationGateState.RequestingPermission,
        LocationGateState.CheckingLocationServices -> {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = FieldForest) }
        }
        LocationGateState.Ready -> {
            LaunchedEffect(Unit) {
                vm.onGatePassed()
                onGatePassed()
            }
            CountingContent(vm = vm, onNotificationGrantRequested = onNotificationGrantRequested)
        }
    }
}

// ---------------------------------------------------------------------------
// Counting content — armband portrait
//
// Layout strategy: ONE weighted child. Status / count card / new-task bar all
// have explicit heights; only the +/- button row uses Modifier.weight(1f) and
// absorbs all leftover space. This is the fix for the prior chained-weight
// layout bug that collapsed the count and the + button to 0 height.
// ---------------------------------------------------------------------------

@Composable
private fun CountingContent(
    vm: CountViewModel,
    onNotificationGrantRequested: () -> Unit = {}
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var showAdmin by remember { mutableStateOf(false) }

    // Audible + haptic cue per press outcome. Held for the screen's lifetime so
    // SoundPool loads its samples once, and released on dispose.
    val feedback = remember(context) { FieldFeedback(context) }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    LaunchedEffect(vm, feedback) {
        vm.pressFeedback.collect { feedback.play(it) }
    }

    if (state.showNewTaskDialog) NewTaskDialog(state, vm)

    if (state.showNotificationPanel) {
        AlertDialog(
            onDismissRequest = { vm.onNotificationPanelIgnore() },
            title = {
                Text("Notifications are off", style = MaterialTheme.typography.headlineMedium)
            },
            text = {
                Text(
                    "You won't see upload progress or completion notifications. Turn them on?",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = onNotificationGrantRequested) {
                    Text("Grant access", style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.onNotificationPanelIgnore() }) {
                    Text("Ignore", style = MaterialTheme.typography.labelLarge)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = FieldInk,
            textContentColor = FieldInkSoft
        )
    }

    if (state.showPushConfirmDialog) {
        AlertDialog(
            onDismissRequest = { vm.onPushConfirmDismissed() },
            title = {
                Text("Push all pending data now?", style = MaterialTheme.typography.headlineMedium)
            },
            text = {
                Text(
                    "${state.pendingTaskCount} task(s) will be sent to the server when Wi-Fi is available. " +
                        "Counting will be locked until upload finishes.",
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.onPushConfirmDismissed()
                    vm.onPushButtonConfirmed()
                }) { Text("Yes, push", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { vm.onPushConfirmDismissed() }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = FieldInk,
            textContentColor = FieldInkSoft
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .alpha(if (state.isManualPushLocked) 0.4f else 1f)
        ) {
            StatusStrip(
                state = state,
                onPushPressStart = vm::onPushPressStart,
                onPushPressCancel = vm::onPushPressCancel,
                onAdminRequested = { showAdmin = true },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            CountCard(
                count = state.count,
                atMax = state.count >= AppConfig.MAX_COUNT_PER_TASK,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
            Spacer(Modifier.height(12.dp))
            ActionRow(
                state = state,
                onMinus = vm::onMinus,
                onPlus = vm::onPlus,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Spacer(Modifier.height(12.dp))
            NewTaskBar(
                state = state,
                onPressStart = vm::onNewTaskPressStart,
                onPressCancel = vm::onNewTaskPressCancel,
                context = context,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.manualPushActive) {
            PushStatusOverlay(
                state = state.pushUiState,
                onCancel = vm::dismissManualPushOverlay,
                onAcknowledge = vm::acknowledgePushOutcome,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }

        if (state.gpsLockState == GpsLockState.LocationDisabled) {
            LocationDisabledOverlay(
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 16.dp)
            )
        }

        if (state.notificationPermissionDenied) {
            NotificationAlertChip(
                offsetXDp = state.notificationChipOffsetX,
                offsetYDp = state.notificationChipOffsetY,
                onTap = vm::onNotificationChipClicked,
                onDrag = { dx, dy -> vm.onNotificationChipDragged(dx, dy) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 16.dp)
            )
        }

        if (showAdmin) {
            com.klk.hams.ui.onboarding.AdminSheet(
                onDismiss = { showAdmin = false },
                // recreate() restarts MainActivity -> isProvisioned()==false -> PairingScreen,
                // so features are not usable after a local clear.
                onReset = { (context as? android.app.Activity)?.recreate() },
            )
        }
    }
}

@Composable
private fun NotificationAlertChip(
    offsetXDp: Float,
    offsetYDp: Float,
    onTap: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    Surface(
        modifier = modifier
            .offset {
                IntOffset(
                    with(density) { offsetXDp.dp.roundToPx() },
                    with(density) { offsetYDp.dp.roundToPx() }
                )
            }
            .size(44.dp)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dxDp = with(density) { drag.x.toDp().value }
                    val dyDp = with(density) { drag.y.toDp().value }
                    onDrag(dxDp, dyDp)
                }
            },
        shape = RoundedCornerShape(50),
        color = FieldEarth,
        contentColor = FieldEarthOn,
        border = BorderStroke(2.dp, FieldEarthOn.copy(alpha = 0.3f))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "!",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = FieldEarthOn
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Manual push status overlay (bottom sheet style)
// ---------------------------------------------------------------------------

@Composable
private fun PushStatusOverlay(
    state: PushUiState,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier
) {
    data class Action(val label: String, val onClick: () -> Unit)
    val (title, body, action) = when (state) {
        is PushUiState.PendingWifi -> Triple(
            "Waiting for network",
            "${state.pendingTasks} task(s) queued. Will push as soon as a network connects. " +
                "Cancel only closes this view; uploads continue in the background.",
            Action("Cancel", onCancel)
        )
        is PushUiState.Pushing -> Triple(
            "Uploading…",
            "Uploading task ${(state.done + 1).coerceAtMost(state.total.coerceAtLeast(1))} of ${state.total}.",
            Action("Cancel", onCancel)
        )
        is PushUiState.Completed -> Triple(
            "Upload complete",
            "${state.tasks} task(s) uploaded.",
            Action("OK", onAcknowledge)
        )
        is PushUiState.Failed -> Triple(
            "Upload paused",
            "${state.reason}. ${state.pending} task(s) remain in cache. Try again later.",
            Action("Close", onAcknowledge)
        )
        is PushUiState.Idle -> return
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FieldHairline)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = FieldInk)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = FieldInkSoft)
            if (state is PushUiState.Pushing && state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = FieldForest,
                    trackColor = FieldHairline
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = action.onClick) {
                Text(action.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Location-disabled overlay (Task 20.3) — blocks counting until GPS is back on.
// ---------------------------------------------------------------------------

@Composable
private fun LocationDisabledOverlay(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.dp, FieldScarlet)
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "GPS is turned off",
                style = MaterialTheme.typography.headlineMedium,
                color = FieldInk
            )
            Text(
                "Recording is paused. Turn GPS back on to continue.",
                style = MaterialTheme.typography.bodyLarge,
                color = FieldInkSoft
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open location settings")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status strip — three single-row pills, no truncation
// ---------------------------------------------------------------------------

@Composable
private fun StatusStrip(
    state: CountUiState,
    onPushPressStart: () -> Unit,
    onPushPressCancel: () -> Unit,
    onAdminRequested: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Robust sizing (ui-ux-pro-max): bounded-content pills (battery/GPS) size
        // to their text with a min-width floor — they never clip, even at "100%"
        // or under large system font scale. The variable-width TASK pill takes the
        // flexible remainder and ellipsizes only as a last resort. No hand-tuned
        // weights that re-break whenever a value's char count changes.
        StatusPill(
            value = "${state.batteryPct}%",
            accent = batteryAccent(state.batteryPct),
            tabularDigits = true,            // steady width as the % changes
            modifier = Modifier.widthIn(min = 60.dp),
            onLongPress = onAdminRequested,
        )
        StatusPill(
            value = gpsLabel(state.gpsLockState),
            accent = gpsAccent(state.gpsLockState),
            modifier = Modifier.widthIn(min = 46.dp)   // "GPS"/"OFF" — frees room for TASK
        )
        StatusPill(
            value = taskValue(state),
            accent = FieldForest,
            modifier = Modifier.weight(1f)
        )
        PushButton(
            enabled = state.pendingTaskCount > 0 && !state.isManualPushLocked,
            holdProgress = state.pushHoldProgress,
            pendingCount = state.pendingTaskCount,
            onPressStart = onPushPressStart,
            onPressCancel = onPushPressCancel
        )
    }
}

@Composable
private fun PushButton(
    enabled: Boolean,
    holdProgress: Float,
    pendingCount: Int,
    onPressStart: () -> Unit,
    onPressCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (enabled) FieldForest else FieldSlate
    val enabledRef by rememberUpdatedState(enabled)
    Box(modifier = modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .size(40.dp)
                .align(Alignment.Center)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (!enabledRef) return@detectTapGestures
                            onPressStart()
                            val released = tryAwaitRelease()
                            if (!released) onPressCancel()
                        }
                    )
                },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(2.dp, borderColor.copy(alpha = 0.3f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    if (holdProgress > 0f) {
                        drawArc(
                            color = borderColor,
                            startAngle = -90f,
                            sweepAngle = 360f * holdProgress,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                        )
                    }
                }
                Text(text = "↑", fontSize = 22.sp, fontWeight = FontWeight.Black, color = borderColor)
            }
        }
        // Pending-count badge — notification-dot at the button's top-right.
        // Visible only when there's something queued. "99+" cap; 28dp circle
        // sized so "99+" (3 chars) fits with room to spare.
        if (pendingCount > 0) {
            val label = if (pendingCount > 99) "99+" else pendingCount.toString()
            Surface(
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.TopEnd)
                    // Float the badge above the button's top-right corner so it
                    // does not overlap the arrow icon. The negative y lifts it
                    // most of the way above the top edge; positive x lets it
                    // protrude past the right edge (standard notification-dot
                    // placement).
                    .offset(x = 6.dp, y = (-8).dp),
                shape = RoundedCornerShape(50),
                color = FieldForest,
                contentColor = FieldForestOn,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // Tight lineHeight + textAlign + includeFontPadding=false so
                    // the glyph centers inside the circle. Default Compose Text
                    // adds baseline padding that visually shifts a small glyph
                    // downward.
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = FieldForestOn,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            }
        }
    }
}

/** Compact single-line pill: colored dot + value. Position in the strip is the
 *  semantic — left = battery, middle = GPS, right = task. */
@Composable
private fun StatusPill(
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    tabularDigits: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .height(56.dp)
            .then(
                if (onLongPress != null)
                    Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FieldHairline)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Auto-shrink to fit: start at 16sp and step down (to an 11sp floor)
            // until the text no longer overflows its slot. Density- and content-proof
            // — the full value is always shown, never clipped, even on a forced-high-
            // density display (here 600dpi => ~320dp width) where a fixed font size
            // truncates. remember(value) re-measures whenever the text changes.
            var fontSizeSp by remember(value) { mutableStateOf(16f) }
            Text(
                value,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                style = if (tabularDigits)
                    LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                else LocalTextStyle.current,
                onTextLayout = { result ->
                    if (result.hasVisualOverflow && fontSizeSp > 11f) {
                        fontSizeSp -= 1f
                    }
                },
            )
        }
    }
}

// (PendingBadge - a standalone pending-count pill - was removed 2026-08-05.
//  It had no callers: the badge that ships is the one drawn inside PushButton.)

// ---------------------------------------------------------------------------
// Count card — fixed 180dp, 120sp digits
// ---------------------------------------------------------------------------

@Composable
private fun CountCard(count: Int, atMax: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, FieldHairline)
    ) {
        Box(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%04d".format(count),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 88.sp,
                    lineHeight = 96.sp,
                    letterSpacing = 0.sp,
                    color = FieldInk,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
                if (atMax) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Maximum reached — start new task",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FieldScarlet,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action row — hero + circle centered, small − circle bottom-left
// ---------------------------------------------------------------------------

@Composable
private fun ActionRow(
    state: CountUiState,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var minusJob by remember { mutableStateOf<Job?>(null) }
    var plusJob by remember { mutableStateOf<Job?>(null) }

    // Feedback is NOT fired here. A press can be refused (stale GPS, at the
    // per-task ceiling, push in progress), and cueing on touch used to promise a
    // count that was never stored. CountViewModel.pressFeedback reports the real
    // outcome; CountScreen plays it.

    // Everything below is sized from the actual space this composable is given
    // (BoxWithConstraints), not fixed dp — so the buttons and their glyphs scale
    // across screen sizes/densities instead of overflowing on smaller devices.
    BoxWithConstraints(modifier = modifier) {
        val gap = 12.dp
        // Minus: ~22% of available width, clamped to a usable touch-target band.
        val minusDiameter = (maxWidth * 0.22f).coerceIn(48.dp, 96.dp)
        // Hero +: the largest square that fits BOTH the full width and the height
        // left after reserving the minus row + gap. coerceAtLeast keeps it a
        // valid size even on extreme/short layouts.
        val heroDiameter = minOf(maxWidth, maxHeight - minusDiameter - gap)
            .coerceAtLeast(48.dp)

        // Glyph sizes derive from each button's pixel diameter, so they scale
        // with the button instead of being pinned to a fixed sp.
        val plusFont = with(density) { (heroDiameter.toPx() * 0.42f).toSp() }
        val plusLine = with(density) { (heroDiameter.toPx() * 0.46f).toSp() }
        val minusFont = with(density) { (minusDiameter.toPx() * 0.50f).toSp() }
        val minusLine = with(density) { (minusDiameter.toPx() * 0.55f).toSp() }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hero + circle centered in the space above the minus row.
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircleActionButton(
                    label = "+",
                    caption = plusCaption(state),
                    labelFontSize = plusFont,
                    labelLineHeight = plusLine,
                    container = if (state.canIncrement) FieldForest else FieldHairline,
                    onContainer = if (state.canIncrement) FieldForestOn else FieldSlate,
                    onPressStart = {
                        onPlus()
                        plusJob?.cancel()
                        plusJob = null
                        // Hold-to-repeat only while presses are being accepted,
                        // so a refused press cues once instead of machine-gunning.
                        if (state.canIncrement) {
                            plusJob = scope.launch {
                                delay(400)
                                while (isActive) {
                                    onPlus()
                                    delay(200)
                                }
                            }
                        }
                    },
                    onPressEnd = { plusJob?.cancel(); plusJob = null },
                    modifier = Modifier.size(heroDiameter)
                )
            }
            Spacer(Modifier.height(gap))
            // Small − circle, left-aligned, immediately above the NEW TASK bar.
            Box(modifier = Modifier.fillMaxWidth()) {
                CircleActionButton(
                    label = "−",
                    caption = null,
                    labelFontSize = minusFont,
                    labelLineHeight = minusLine,
                    container = if (state.canDecrement) FieldEarth else FieldHairline,
                    onContainer = if (state.canDecrement) FieldEarthOn else FieldSlate,
                    onPressStart = {
                        onMinus()
                        minusJob?.cancel()
                        minusJob = null
                        if (state.canDecrement) {
                            minusJob = scope.launch {
                                delay(400)
                                while (isActive) {
                                    onMinus()
                                    delay(200)
                                }
                            }
                        }
                    },
                    onPressEnd = { minusJob?.cancel(); minusJob = null },
                    modifier = Modifier.size(minusDiameter).align(Alignment.CenterStart)
                )
            }
        }
    }
}

@Composable
private fun CircleActionButton(
    label: String,
    caption: String?,
    container: Color,
    onContainer: Color,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    labelFontSize: TextUnit = 88.sp,
    labelLineHeight: TextUnit = 96.sp
) {
    // pointerInput keyed on Unit, never on a changing enabled flag. Keying on
    // one caused a gesture-replay bug: pressing + made canDecrement flip
    // false→true on the sibling button, re-keying its pointerInput, which
    // cancelled+restarted the gesture detector — and any in-flight pointer could
    // replay as a new press, firing onMinus on a sibling that was just enabled.
    // Keying on Unit keeps the detector alive for the composable's lifetime, so
    // there is nothing to restart and nothing to replay.
    //
    // The press is deliberately NOT gated here any more. Every press reaches the
    // ViewModel so a refusal can be announced (2026-08-19: a disabled button
    // swallowed stale-GPS presses in silence, which field workers reported as
    // "no vibration"). The ViewModel remains the sole authority on whether a
    // count is recorded — GPS gating is unchanged.
    Surface(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onPressStart()
                    tryAwaitRelease()
                    onPressEnd()
                }
            )
        },
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = onContainer
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = labelFontSize,
                    lineHeight = labelLineHeight,
                    color = onContainer
                )
                if (caption != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        caption,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        color = onContainer,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// New task bar — fixed 88dp + linear progress above
// ---------------------------------------------------------------------------

@Composable
private fun NewTaskBar(
    state: CountUiState,
    onPressStart: () -> Unit,
    onPressCancel: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    val currentCount by rememberUpdatedState(state.count)
    val currentProgress by rememberUpdatedState(state.newTaskProgress)
    val active = state.count > 0

    Column(modifier = modifier) {
        if (state.newTaskProgress > 0f) {
            LinearProgressIndicator(
                progress = { state.newTaskProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(bottom = 4.dp),
                color = FieldForest,
                trackColor = FieldHairline
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                // Key on Unit (not currentCount) — same reason as
                // BigActionButton. currentCount is already a rememberUpdatedState
                // above, so it stays fresh inside the gesture without re-keying.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            if (currentCount <= 0) {
                                Toast.makeText(
                                    context,
                                    "Record at least one cut before starting a new task.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onPressStart()
                                val released = tryAwaitRelease()
                                if (!released || currentProgress < 1f) onPressCancel()
                            }
                        }
                    )
                },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (active) FieldForest else FieldHairline
            )
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (active) "HOLD 3s  ·  NEW TASK" else "NEW TASK",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = if (active) FieldForest else FieldSlate,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dialog
// ---------------------------------------------------------------------------

@Composable
private fun NewTaskDialog(state: CountUiState, vm: CountViewModel) {
    AlertDialog(
        onDismissRequest = { vm.onNewTaskDismissed() },
        title = {
            Text(
                "Save task?",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Text(
                "Save current task (${state.count} cuts) and prepare for the next task?",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.onNewTaskConfirmed() }) {
                Text("Yes, save", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.onNewTaskDismissed() }) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = FieldInk,
        textContentColor = FieldInkSoft
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** The GPS pill normally reads "GPS"; the dot + text colour carry the state.
 *  Green = working / can press, amber = waiting / cannot press, slate = no perm,
 *  scarlet "OFF" = location services disabled mid-session (Task 20.3). */
private fun gpsLabel(state: GpsLockState): String = when (state) {
    GpsLockState.LocationDisabled -> "OFF"
    else -> "GPS"
}

private fun gpsAccent(state: GpsLockState): Color = when (state) {
    GpsLockState.Locked -> FieldForest
    GpsLockState.Stale, GpsLockState.Acquiring -> FieldAmber
    GpsLockState.NoPermission -> FieldSlate
    GpsLockState.LocationDisabled -> FieldScarlet
}

private fun batteryAccent(pct: Int): Color = when {
    pct in 1..9 -> FieldScarlet
    pct in 10..19 -> FieldAmber
    else -> FieldInk
}

private fun taskValue(state: CountUiState): String {
    val seq = state.taskSeq
    val activeDate = state.taskDate
    val today = state.todayDate
    return when {
        // Active task with its own date
        seq != null && activeDate.isNotEmpty() -> "#$seq · ${shortDate(activeDate)}"
        // Active task without date (legacy / shouldn't happen post-migration)
        seq != null -> "#$seq"
        // Pre-press preview: show today's MYT date next to the next-seq
        // number. "Next " prefix removed (2026-05-13) — it pushed the date
        // out and caused truncation; count = 0 already signals pre-press.
        today.isNotEmpty() -> "#${state.pendingTaskSeq} · ${shortDate(today)}"
        else -> "#${state.pendingTaskSeq}"
    }
}

private fun plusCaption(state: CountUiState): String = when {
    state.count >= AppConfig.MAX_COUNT_PER_TASK -> "MAX"
    state.gpsLockState != GpsLockState.Locked -> "WAIT"
    else -> "ADD"
}

/** "2026-06-29" -> "Jun29" (compact, no space — fits the narrow TASK pill on any
 *  width / font scale). Returns input on parse failure. */
private fun shortDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMd", Locale.ENGLISH))
}.getOrElse { iso }

@Composable
private fun BlockingMessage(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(1_800)
        onDismiss()
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = FieldInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}
