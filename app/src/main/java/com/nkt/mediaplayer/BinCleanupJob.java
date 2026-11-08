package com.nkt.mediaplayer;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Once a day, even when the app isn't opened: anything in the Recycle bin for 30 days is deleted permanently.
 * (Also done whenever the app or the Recycle bin opens.)
 */
public class BinCleanupJob extends JobService {

    private static final int JOB_ID = 7101;

    /** Called at start; does nothing if the daily job is already set. */
    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BinCleanupJob.class))
                    .setPeriodic(24L * 60 * 60 * 1000)
                    .setPersisted(true)                     // keeps running after the phone restarts
                    .setRequiresBatteryNotLow(true)
                    .build());
        } catch (Throwable ignored) { }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { Trash.purgeOld(getApplicationContext()); } catch (Throwable ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
