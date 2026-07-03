package com.klk.hams.ui.count

import com.klk.hams.AppConfig
import com.klk.hams.data.location.GpsLockState

data class CountUiState(
    val count: Int = 0,
    /** null = no active task row yet (before first + press) */
    val taskSeq: Int? = null,
    /** MYT calendar day of the active task; empty until the first + press */
    val taskDate: String = "",
    /** Today's MYT calendar day, refreshed on every state tick. Used as the
     *  preview date in the TASK pill before the first + press of the day. */
    val todayDate: String = "",
    /** shown as "Next Task #N" before the first + press of a new task */
    val pendingTaskSeq: Int = 1,
    val batteryPct: Int = 0,
    val gpsLockState: GpsLockState = GpsLockState.NoPermission,
    val newTaskProgress: Float = 0f,
    val showNewTaskDialog: Boolean = false,
    val pushUiState: com.klk.hams.push.PushUiState = com.klk.hams.push.PushUiState.Idle,
    val pendingTaskCount: Int = 0,
    val manualPushActive: Boolean = false,
    val pushHoldProgress: Float = 0f,
    val showPushConfirmDialog: Boolean = false,
    val notificationPermissionDenied: Boolean = false,
    val showNotificationPanel: Boolean = false,
    val notificationChipOffsetX: Float = 0f,
    val notificationChipOffsetY: Float = 0f,
) {
    val isTaskActive: Boolean get() = taskSeq != null

    /** True while a manual push is in progress (UI must dim and lock counting). */
    val isManualPushLocked: Boolean
        get() = manualPushActive && pushUiState.isLockable

    val canDecrement: Boolean get() = count > 0 && gpsLockState == GpsLockState.Locked && !isManualPushLocked
    val canIncrement: Boolean
        get() = count < AppConfig.MAX_COUNT_PER_TASK && gpsLockState == GpsLockState.Locked && !isManualPushLocked
    val taskLabel: String get() = if (taskSeq != null) "Task #$taskSeq" else "Next Task #$pendingTaskSeq"
}
