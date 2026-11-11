package com.spizganed.quickbuds.ui

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper

/**
 * The 6 h background update check. It fetches off the main thread and posts one notification for a
 * newer release that was not announced. The job needs a network and is scheduled again on each app
 * open, so it is not persisted across reboots.
 */
class UpdateJobService : JobService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            UpdateChecker.runBackground(this)
            main.post { jobFinished(params, false) }
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
