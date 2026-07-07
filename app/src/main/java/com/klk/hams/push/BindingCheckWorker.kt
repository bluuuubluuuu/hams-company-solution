package com.klk.hams.push

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.klk.hams.HamsApp
import com.klk.hams.provisioning.ProvisioningStore

/**
 * Periodic binding re-check. Only self-unprovisions on an explicit
 * released/bound_other; network failure is a silent no-op.
 */
class BindingCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (PushWorker.pushInProgress) {
            Log.d(TAG, "binding check skipped: push in progress")
            return Result.success()
        }

        val store = ProvisioningStore.fromContext(applicationContext)
        if (!store.isProvisioned()) return Result.success()

        return try {
            (applicationContext as HamsApp).bindingRevalidator.revalidate()
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "binding check failed: $t", t)
            Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "hams-binding-check"
        private const val TAG = "HAMS_PUSH"
    }
}
