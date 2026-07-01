package com.klk.hams.ui.count

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.klk.hams.AppConfig
import com.klk.hams.HamsApp
import com.klk.hams.data.location.LocationStream
import com.klk.hams.data.repository.TaskRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class CountViewModel(
    application: Application,
    private val repository: TaskRepository,
    private val locationStream: LocationStream,
    private val batteryMonitor: BatteryMonitor
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CountUiState())
    val uiState: StateFlow<CountUiState> = _uiState.asStateFlow()

    private var nextTaskSeq: Int = 1
    private var newTaskHoldJob: Job? = null
    private var gateGranted: Boolean = false

    private val staleHandler = Handler(Looper.getMainLooper())
    private val staleTick = object : Runnable {
        override fun run() {
            recomputeGpsLock()
            refreshTodayDate()
            viewModelScope.launch {
                repository.rolloverActiveTaskIfStale()?.let { rolledId ->
                    Log.d(TAG, "tick rollover: finalized stale task id=$rolledId")
                }
            }
            staleHandler.postDelayed(this, STALE_RECHECK_MS)
        }
    }

    private fun mytToday(): String =
        LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString()

    private fun refreshTodayDate() {
        val today = mytToday()
        if (_uiState.value.todayDate != today) {
            _uiState.update { it.copy(todayDate = today) }
        }
    }

    init {
        batteryMonitor.register()

        // Seed today's date so the TASK pill shows "Next #N · today" even
        // before any GPS tick.
        _uiState.update { it.copy(todayDate = mytToday()) }

        viewModelScope.launch {
            batteryMonitor.batteryPctFlow.collect { pct ->
                _uiState.update { it.copy(batteryPct = pct) }
            }
        }

        viewModelScope.launch {
            // Day rollover: if yesterday's task is still active, finalize it
            // before observing the active-task flow. Today's first + press
            // then lazy-creates a fresh task with task_seq=1.
            val rolled = repository.rolloverActiveTaskIfStale()
            if (rolled != null) {
                Log.d(TAG, "rollover: finalized stale task id=$rolled (yesterday or older)")
            }
            nextTaskSeq = repository.getNextTaskSeq()
            repository.observeActiveTask().collect { task ->
                if (task == null) {
                    nextTaskSeq = repository.getNextTaskSeq()
                }
                Log.d(TAG, "observeActiveTask emitted: id=${task?.id}, seq=${task?.taskSeq}, " +
                    "plus=${task?.plusCount}, minus=${task?.minusCount}, net=${task?.netCount}")
                _uiState.update { s ->
                    s.copy(
                        count = task?.netCount ?: 0,
                        taskSeq = task?.taskSeq,
                        taskDate = task?.taskDate ?: "",
                        pendingTaskSeq = nextTaskSeq
                    )
                }
            }
        }

        viewModelScope.launch {
            locationStream.snapshotFlow.collect { recomputeGpsLock() }
        }

        viewModelScope.launch {
            getApplication<HamsApp>().pushController.uiStateFlow.collect { pushUi ->
                _uiState.update { s ->
                    val clearLock = pushUi is com.klk.hams.push.PushUiState.Completed ||
                        pushUi is com.klk.hams.push.PushUiState.Failed ||
                        pushUi is com.klk.hams.push.PushUiState.Idle
                    s.copy(
                        pushUiState = pushUi,
                        manualPushActive = if (clearLock) false else s.manualPushActive
                    )
                }
            }
        }
        viewModelScope.launch {
            getApplication<HamsApp>().pushController.pendingCountFlow.collect { count ->
                _uiState.update { it.copy(pendingTaskCount = count) }
            }
        }

        staleHandler.postDelayed(staleTick, STALE_RECHECK_MS)
    }

    fun onGatePassed() {
        gateGranted = true
        recomputeGpsLock()
    }

    private fun recomputeGpsLock() {
        if (!gateGranted) {
            _uiState.update { it.copy(gpsLockState = GpsLockState.NoPermission) }
            return
        }
        // Mid-session location-services-off detection (Task 20.3). The launch gate
        // runs once; if the user turns GPS off later this catches it.
        val lm = getApplication<HamsApp>()
            .getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locEnabled) {
            _uiState.update { it.copy(gpsLockState = GpsLockState.LocationDisabled) }
            return
        }
        val snapshot = locationStream.snapshotFlow.value
        if (snapshot == null) {
            _uiState.update { it.copy(gpsLockState = GpsLockState.Acquiring) }
            return
        }
        val age = SystemClock.elapsedRealtime() - snapshot.capturedAtMs
        val newState = nextGpsLockState(_uiState.value.gpsLockState, age)
        _uiState.update { it.copy(gpsLockState = newState) }
    }

    fun onPlus() {
        val state = _uiState.value
        if (!state.canIncrement) {
            Log.d(TAG, "onPlus REJECTED: count=${state.count}, lock=${state.gpsLockState}")
            return
        }
        val snapshot = locationStream.snapshotFlow.value
        if (snapshot == null || !LocationStream.isFresh(snapshot)) {
            Log.d(TAG, "onPlus REJECTED: snapshot null or stale")
            return
        }
        val battery = batteryMonitor.currentPct().toDouble()
        Log.d(TAG, "onPlus ACCEPTED: count=${state.count} -> recording")
        viewModelScope.launch {
            val result = repository.recordPlus(snapshot, battery)
            Log.d(TAG, "onPlus DONE: recorded eventId=${result?.id}, workCount=${result?.workCount}")
        }
    }

    fun onMinus() {
        val state = _uiState.value
        if (!state.canDecrement) {
            Log.d(TAG, "onMinus REJECTED: count=${state.count}, lock=${state.gpsLockState}")
            return
        }
        val snapshot = locationStream.snapshotFlow.value
        if (snapshot == null || !LocationStream.isFresh(snapshot)) {
            Log.d(TAG, "onMinus REJECTED: snapshot null or stale")
            return
        }
        val battery = batteryMonitor.currentPct().toDouble()
        Log.d(TAG, "onMinus ACCEPTED: count=${state.count} -> recording")
        viewModelScope.launch {
            val result = repository.recordMinus(snapshot, battery)
            Log.d(TAG, "onMinus DONE: recorded eventId=${result?.id}, workCount=${result?.workCount}")
        }
    }

    fun onPushButtonConfirmed() {
        _uiState.update { it.copy(manualPushActive = true) }
        getApplication<HamsApp>().pushController.triggerManual()
        Log.d(TAG, "manual push triggered")
    }

    fun dismissManualPushOverlay() {
        getApplication<HamsApp>().pushController.dismissManualOverlay()
        _uiState.update { it.copy(manualPushActive = false) }
        Log.d(TAG, "manual overlay dismissed; worker continues")
    }

    fun acknowledgePushOutcome() {
        getApplication<HamsApp>().pushController.acknowledgeCompletion()
        _uiState.update { it.copy(manualPushActive = false) }
    }

    fun onNewTaskPressStart() {
        if (_uiState.value.count <= 0) return
        newTaskHoldJob?.cancel()
        newTaskHoldJob = viewModelScope.launch {
            // 100 ticks x 30 ms = 3 000 ms (reduced from 5 s — field feedback 2026-05-13)
            repeat(100) { i ->
                delay(30)
                _uiState.update { it.copy(newTaskProgress = (i + 1) / 100f) }
            }
            _uiState.update { it.copy(showNewTaskDialog = true) }
        }
    }

    fun onNewTaskPressCancel() {
        newTaskHoldJob?.cancel()
        newTaskHoldJob = null
        _uiState.update { state ->
            if (state.showNewTaskDialog) {
                state
            } else {
                state.copy(newTaskProgress = 0f)
            }
        }
    }

    fun onNewTaskConfirmed() {
        viewModelScope.launch {
            if (_uiState.value.count <= 0) {
                _uiState.update { it.copy(showNewTaskDialog = false, newTaskProgress = 0f) }
                return@launch
            }
            val battery = batteryMonitor.currentPct().toDouble()
            repository.saveActiveTask("manual", null, battery)
            nextTaskSeq = repository.getNextTaskSeq()
            _uiState.update {
                it.copy(
                    showNewTaskDialog = false,
                    newTaskProgress = 0f,
                    pendingTaskSeq = nextTaskSeq
                )
            }
        }
    }

    private var pushHoldJob: Job? = null

    fun onPushPressStart() {
        if (_uiState.value.pendingTaskCount <= 0) return
        pushHoldJob?.cancel()
        pushHoldJob = viewModelScope.launch {
            // 100 ticks x 30 ms = 3 000 ms (matches NEW TASK hold — field feedback 2026-05-13)
            repeat(100) { i ->
                delay(30)
                _uiState.update { it.copy(pushHoldProgress = (i + 1) / 100f) }
            }
            _uiState.update { it.copy(showPushConfirmDialog = true) }
        }
    }

    fun onPushPressCancel() {
        pushHoldJob?.cancel()
        pushHoldJob = null
        _uiState.update { state ->
            if (state.showPushConfirmDialog) state else state.copy(pushHoldProgress = 0f)
        }
    }

    fun onPushConfirmDismissed() {
        _uiState.update { it.copy(showPushConfirmDialog = false, pushHoldProgress = 0f) }
    }

    fun setNotificationPermissionDenied(denied: Boolean) {
        _uiState.update {
            it.copy(
                notificationPermissionDenied = denied,
                showNotificationPanel = if (!denied) false else it.showNotificationPanel
            )
        }
    }

    fun onNotificationChipClicked() {
        _uiState.update { it.copy(showNotificationPanel = true) }
    }

    fun onNotificationPanelIgnore() {
        _uiState.update { it.copy(showNotificationPanel = false) }
    }

    fun onNotificationChipDragged(dx: Float, dy: Float) {
        _uiState.update {
            it.copy(
                notificationChipOffsetX = it.notificationChipOffsetX + dx,
                notificationChipOffsetY = it.notificationChipOffsetY + dy
            )
        }
    }

    fun onNotificationGrantDispatched() {
        _uiState.update { it.copy(showNotificationPanel = false) }
    }

    fun onNewTaskDismissed() {
        newTaskHoldJob?.cancel()
        newTaskHoldJob = null
        _uiState.update { it.copy(showNewTaskDialog = false, newTaskProgress = 0f) }
    }

    override fun onCleared() {
        super.onCleared()
        staleHandler.removeCallbacks(staleTick)
        batteryMonitor.unregister()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = application as HamsApp
            return CountViewModel(
                application,
                app.repository,
                app.locationStream,
                BatteryMonitor(application)
            ) as T
        }
    }

    companion object {
        private const val STALE_RECHECK_MS: Long = 1_000
        private const val TAG = "HAMS_UI"

        /**
         * Pure hysteresis decision (Task 20). No Android deps — JVM testable.
         *
         * From Locked: stay Locked unless age exceeds [staleAfterMs].
         * From any non-Locked state: flip to Locked only when age drops below
         * [relockBelowMs]; otherwise treat as Stale. This intentionally creates
         * a no-flip band between [relockBelowMs] and [staleAfterMs] so the pill
         * doesn't flicker at the staleness boundary.
         */
        internal fun nextGpsLockState(
            current: GpsLockState,
            ageMs: Long,
            staleAfterMs: Long = AppConfig.GPS_LOCK_STALE_AFTER_MS,
            relockBelowMs: Long = AppConfig.GPS_LOCK_RELOCK_BELOW_MS
        ): GpsLockState = when (current) {
            GpsLockState.Locked ->
                if (ageMs > staleAfterMs) GpsLockState.Stale else GpsLockState.Locked
            GpsLockState.Stale,
            GpsLockState.Acquiring,
            GpsLockState.NoPermission,
            GpsLockState.LocationDisabled ->
                if (ageMs < relockBelowMs) GpsLockState.Locked else GpsLockState.Stale
        }
    }
}
