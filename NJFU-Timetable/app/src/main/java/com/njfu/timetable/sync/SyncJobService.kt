package com.njfu.timetable.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.njfu.timetable.data.AppPrefs

/**
 * 后台静默同步：每 6 小时尝试一次，失败则静默等待下次，
 * 不做任何轮询和唤醒，避免耗电。
 */
class SyncJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            val prefs = AppPrefs(this)
            if (prefs.username.isNotEmpty() && prefs.cookies.isNotEmpty()) {
                SyncEngine.refresh(this)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 4102

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val existing = scheduler.allPendingJobs?.any { it.id == JOB_ID } ?: false
            if (existing) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(info)
        }
    }
}
