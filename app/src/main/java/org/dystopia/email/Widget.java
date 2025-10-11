package org.dystopia.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018, Marcel Bokhorst (M66B)
*/

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class Widget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        update(appWidgetIds, appWidgetManager, context, -1);
    }

    static void update(Context context, int count) {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        int[] appWidgetIds =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, Widget.class));
        update(appWidgetIds, appWidgetManager, context, count);
    }

    private static void update(
        int[] appWidgetIds, AppWidgetManager appWidgetManager, Context context, int count) {
        Intent intent = new Intent(context, ActivityView.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi =
            PendingIntent.getActivity(
                context, ActivityView.REQUEST_UNIFIED, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (int id : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
            views.setOnClickPendingIntent(R.id.widget, pi);
            views.setTextViewText(R.id.tvCount, count < 0 ? "?" : Integer.toString(count));
            appWidgetManager.updateAppWidget(id, views);
        }
    }
}
