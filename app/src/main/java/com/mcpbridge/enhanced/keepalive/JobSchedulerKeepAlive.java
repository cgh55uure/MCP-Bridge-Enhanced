package com.mcpbridge.enhanced.keepalive;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * JobScheduler 保活 - 保活第二层：利用系统 JobScheduler 定期唤醒
 */
public class JobSchedulerKeepAlive extends JobService {

    private static final int JOB_ID = 0x1001;
    private static final long INTERVAL_MS = 15 * 60 * 1000L; // 15分钟

    @Override
    public boolean onStartJob(JobParameters params) {
        // 检查各服务状态，必要时重启
        KeepAliveManager.getInstance(this).checkAndRestart();
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 需要重试
    }

    /**
     * 调度周期性 Job
     */
    public static void schedule(Context context) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler == null) return;

        ComponentName componentName = new ComponentName(context, JobSchedulerKeepAlive.class);

        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, componentName)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true) // 设备重启后保留
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setRequiresBatteryNotLow(false);
        }

        if (jobScheduler.schedule(builder.build()) <= 0) {
            // 调度失败，可能是 Job 已存在，先取消再重试
            jobScheduler.cancel(JOB_ID);
            jobScheduler.schedule(builder.build());
        }
    }

    /**
     * 取消 Job
     */
    public static void cancel(Context context) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobScheduler != null) {
            jobScheduler.cancel(JOB_ID);
        }
    }
}