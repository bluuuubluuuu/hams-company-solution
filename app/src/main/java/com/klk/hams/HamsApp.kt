package com.klk.hams

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.klk.hams.data.db.AppDatabase
import com.klk.hams.data.db.MIGRATION_1_2
import com.klk.hams.data.db.MIGRATION_2_3
import com.klk.hams.data.db.MIGRATION_3_4
import com.klk.hams.data.db.MIGRATION_4_5
import com.klk.hams.data.location.LocationStream
import com.klk.hams.data.repository.TaskRepository
import com.klk.hams.push.BindingCheckWorker
import com.klk.hams.provisioning.ProvisioningStore
import com.klk.hams.provisioning.shouldAutoPush
import com.klk.hams.service.HamsForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.klk.hams.push.PushUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class HamsApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "hams.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    val repository: TaskRepository by lazy {
        TaskRepository(
            db = database,
            deviceIdProvider = ProvisioningStore.fromContext(this).uniqueIdProvider(),
        )
    }

    val locationStream: LocationStream by lazy { LocationStream(applicationContext) }

    val pushController: com.klk.hams.push.PushController by lazy {
        com.klk.hams.push.PushController(applicationContext, repository)
    }

    /** null = provisioned/active; non-null = revoked, value is the banner text. */
    val provisioningRevocation = MutableStateFlow<String?>(null)

    val bindingRevalidator: com.klk.hams.provisioning.BindingRevalidator by lazy {
        com.klk.hams.provisioning.BindingRevalidator(this)
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createServiceChannel()
        com.klk.hams.push.PushNotifier.ensureChannel(this)
        val provisioningStore = ProvisioningStore.fromContext(this)
        repository.onTaskFinalized = {
            if (provisioningStore.isProvisioned()) {
                pushController.enqueueAuto()
            } else {
                Log.d("HAMS_PUSH", "onTaskFinalized: device is not provisioned; skipping auto-push")
            }
        }

        // Retention sweep (Codex adversarial-fix design §4, 2026-05-15) —
        // deletes 'uploaded'/'failed'/'discarded' tasks older than the
        // configured retention. Runs once per process launch on a background
        // dispatcher; no UI blocking. Active and pending tasks are never
        // touched.
        applicationScope.launch {
            try {
                val deleted = repository.purgeStaleTerminalTasks()
                if (deleted > 0) {
                    Log.d("HAMS_PUSH", "onCreate: retention sweep deleted $deleted stale task(s)")
                }
                val diagDeleted = repository.purgeStaleDiagnostics()
                if (diagDeleted > 0) {
                    Log.d("HAMS_PUSH", "onCreate: retention sweep deleted $diagDeleted stale diagnostic(s)")
                }
            } catch (t: Throwable) {
                Log.w("HAMS_PUSH", "onCreate: retention sweep failed: $t", t)
            }
        }

        // Boot detection fallback: BootReceiver (ACTION_BOOT_COMPLETED) is
        // unreliable when the app was force-stopped/swipe-killed (stopped state)
        // or when an OEM ROM blocks boot broadcasts. The first app run of a new
        // boot session records the boot the receiver missed; deduped by boot
        // instant so a real boot is logged exactly once regardless of which path
        // fires.
        applicationScope.launch {
            try {
                com.klk.hams.diagnostics.BootReceiver.recordBootIfNew(applicationContext)
            } catch (t: Throwable) {
                Log.w("HAMS_PUSH", "onCreate: boot fallback failed: $t", t)
            }
        }

        // Spec §18: closes the post-force-stop / fresh-launch gap.
        // Force-stop wipes WorkManager's queue but not SQLite; if pending rows
        // exist, re-arm auto-push so it fires when validated Wi-Fi appears.
        applicationScope.launch {
            val pendingTasks = repository.observePendingTaskCount().first()
            val pendingTelemetry = repository.countPendingTelemetry()
            val pending = pendingTasks + pendingTelemetry
            if (shouldAutoPush(isProvisioned = provisioningStore.isProvisioned(), pending = pending)) {
                Log.d("HAMS_PUSH", "onCreate: $pending pending item(s) found; enqueuing auto-push")
                pushController.enqueueAuto()
                // NOTE: do NOT start HamsForegroundService here. Application.onCreate
                // runs during process bootstrap with no foreground Activity. Starting
                // a location-typed FGS from that context fails Android 15's foreground-
                // eligibility check for ACCESS_FINE_LOCATION and crashes the process.
                // WorkManager (above) runs PushWorker on its own dataSync FGS — that
                // path is not subject to this restriction. When the user opens the app,
                // MainActivity.startHamsService brings the monitor up legally.
            }
        }

        // Phase 3.3c — task-cap re-enqueue chain.
        // PushWorker drains at most PUSH_TASK_BATCH_LIMIT tasks per run. When a
        // run completes with forward progress AND more tasks remain pending,
        // chain the next run. Forward-progress guard (tasks > 0) prevents an
        // infinite loop when only server-rejected tasks remain (those stay
        // pending forever and never re-drain).
        applicationScope.launch {
            var lastHandledCompletion: Instant? = null
            pushController.uiStateFlow.collect { state ->
                if (state !is PushUiState.Completed) return@collect
                if (state.at == lastHandledCompletion) return@collect
                if (state.tasks <= 0) return@collect
                val pending = pushController.pendingCountFlow.value
                if (!shouldAutoPush(isProvisioned = provisioningStore.isProvisioned(), pending = pending)) return@collect
                lastHandledCompletion = state.at
                Log.d("HAMS_PUSH", "chain: completion uploaded=${state.tasks} pending=$pending; enqueuing next")
                pushController.enqueueAuto()
            }
        }

        // Binding revalidation: launch-time one-shot. Released defers flush to
        // PushWorker; bound_other logs out without pushing.
        applicationScope.launch {
            try {
                if (provisioningStore.isProvisioned()) bindingRevalidator.revalidate()
            } catch (t: Throwable) {
                Log.w("HAMS_PUSH", "onCreate: launch binding check failed: $t", t)
            }
        }

        // Binding revalidation: periodic, network-connected, unique WorkManager job.
        val bindingCheck = PeriodicWorkRequestBuilder<BindingCheckWorker>(
            AppConfig.BINDING_CHECK_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BindingCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            bindingCheck,
        )
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HamsForegroundService.CHANNEL_ID,
                "HAMS Task Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HAMS background service - keeps task data safe"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
