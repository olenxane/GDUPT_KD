package com.aggapp.app.di

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aggapp.platform.api.AppLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** 后台定时保活：App 未打开时也定期轻量请求各平台，降低会话因闲置过期的概率 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun keeper(): SessionKeeper
    }

    override suspend fun doWork(): Result {
        val keeper = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).keeper()
        return try {
            keeper.keepAlive(minIntervalMs = MIN_GAP_MS)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Keeper", "后台保活失败", e)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "session_keep_alive"
        private const val PERIOD_HOURS = 4L
        private const val MIN_GAP_MS = 10 * 60_000L

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
