package com.omnix.podstash.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.omnix.podstash.PodstashApp
import java.util.concurrent.TimeUnit

object AutoScanScheduler {
    const val PERIODIC = "omnix-auto-scan"
    const val ONCE = "omnix-auto-scan-once"

    fun ensure(context: Context) {
        val app = context.applicationContext
        val store = (app as? PodstashApp)?.store ?: return
        val wm = WorkManager.getInstance(app)
        if (!store.autoScan) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val days = store.autoScanDays.coerceIn(1, 30).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (store.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<AutoScanWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runOnce(context: Context) {
        val app = context.applicationContext
        val store = (app as? PodstashApp)?.store
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (store?.wifiOnly == true) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val req = OneTimeWorkRequestBuilder<AutoScanWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("once" to true))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}
