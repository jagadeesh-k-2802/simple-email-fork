package org.dystopia.email.util;

/*
  This file is part of SimpleEmail.

  SimpleEmail is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  SimpleEmail is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with SimpleEmail.  If not, see <http://www.gnu.org/licenses/>.

  Copyright 2018-2020, Distopico (dystopia project) <distopico@riseup.net> and contributors
*/

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobScheduler;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.inputmethod.InputMethodManager;

import org.dystopia.email.BuildConfig;

public class CompatibilityUtils {

    static public NotificationManager getNotificationManger(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(NotificationManager.class);
        }
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static public PowerManager getPowerManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(PowerManager.class);
        }
        return (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    static public AlarmManager getAlarmManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(AlarmManager.class);
        }
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    static public ConnectivityManager getConnectivityManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(ConnectivityManager.class);
        }
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    static public InputMethodManager getInputMethodManager(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(InputMethodManager.class);
        }
        return (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    static public JobScheduler getJobScheduler(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.getSystemService(JobScheduler.class);
        } else {
            return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        }
    }

    static public ClipboardManager getClipboardManager(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return context.getSystemService(ClipboardManager.class);
        }
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    static public Boolean isIgnoringOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = getPowerManager(context);
            if (powerManager != null) {
                return powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID);
            }
        }
        return true;
    }

    static public void setAndAllowWhileIdle(AlarmManager alarmManager, int type, long triggerAtMillis, PendingIntent operation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(type, triggerAtMillis, operation);
            return;
        }
        alarmManager.setExact(type, triggerAtMillis, operation);
    }
}
