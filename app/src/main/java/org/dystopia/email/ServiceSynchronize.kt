/*
 * This file is part of FairEmail.
 *
 * FairEmail is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * FairEmail is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with FairEmail. If not,
 * see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2018, Marcel Bokhorst (M66B)
 * Copyright 2018-2023, Distopico (dystopia project) <distopico@riseup.net> and contributors
 */
package org.dystopia.email

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.ContactsContract
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.util.LongSparseArray
import android.util.Pair
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sun.mail.iap.ConnectionException
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.IMAPStore
import com.sun.mail.util.FolderClosedIOException
import com.sun.mail.util.MailConnectException
import org.dystopia.email.util.CompatibilityHelper.getAlarmManager
import org.dystopia.email.util.CompatibilityHelper.getConnectivityManager
import org.dystopia.email.util.CompatibilityHelper.getNotificationManger
import org.dystopia.email.util.CompatibilityHelper.getPowerManager
import org.dystopia.email.util.CompatibilityHelper.setAndAllowWhileIdle
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.FolderClosedException
import javax.mail.FolderNotFoundException
import javax.mail.Message
import javax.mail.MessageRemovedException
import javax.mail.MessagingException
import javax.mail.NoSuchProviderException
import javax.mail.SendFailedException
import javax.mail.Session
import javax.mail.StoreClosedException
import javax.mail.UIDFolder
import javax.mail.event.ConnectionAdapter
import javax.mail.event.ConnectionEvent
import javax.mail.event.FolderAdapter
import javax.mail.event.FolderEvent
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import javax.mail.event.StoreEvent
import javax.mail.event.StoreListener
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm
import javax.net.ssl.SSLException

class ServiceSynchronize : LifecycleService() {
    private val lock = Any()
    private var lastStats: TupleAccountStats? = null
    private val serviceManager = ServiceManager()
    override fun onCreate() {
        Log.i(Helper.TAG, "Service create version=" + BuildConfig.VERSION_NAME)
        super.onCreate()

        // Listen for network changes
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = NetworkRequest.Builder()
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // Removed because of Android VPN service
        // builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        cm.registerNetworkCallback(builder.build(), serviceManager)
        val db = DB.getInstance(this)
        db.account().liveStats().observe(this) { stats ->
            val notificationManager = getNotificationManger(baseContext)
            notificationManager.notify(
                NOTIFICATION_SYNCHRONIZE,
                getNotificationService(stats).build()
            )
        }
        db.message().liveUnseenUnified().observe(this, object : Observer<List<TupleNotification>> {
            private val notifying = LongSparseArray<MutableList<Int>>()
            private val accounts = LongSparseArray<Pair<*, *>>()
            override fun onChanged(messages: List<TupleNotification>) {
                val notificationManager = getNotificationManger(baseContext)
                val messagesByAccount = LongSparseArray<MutableList<TupleNotification>>()
                val removed = notifying.clone()

                // Update unseen for all account
                setWidgetUnseen(messages)
                if (messages.size == 0) {
                    notificationManager.cancelAll()
                    return
                }

                // Organize messages per account
                for (message in messages) {
                    val accountKey = message.account
                    var msgList: MutableList<TupleNotification> = ArrayList()
                    if (messagesByAccount.indexOfKey(accountKey) >= 0) {
                        msgList = messagesByAccount[accountKey, msgList]
                    }
                    if (accounts.indexOfKey(accountKey) < 0) {
                        accounts.put(
                            accountKey,
                            Pair(message.accountName, message.accountColor)
                        )
                    }
                    msgList.add(message)
                    messagesByAccount.put(accountKey, msgList)
                }

                // Set and group notification per account
                for (i in 0 until messagesByAccount.size()) {
                    val accountId = messagesByAccount.keyAt(i)
                    val messagesAccount: List<TupleNotification> = messagesByAccount[accountId]
                    val notifications = getNotificationUnseen(messagesAccount, accounts[accountId])
                    val all: MutableList<Int> = ArrayList()
                    val added: MutableList<Int> = ArrayList()
                    var toRemove: MutableList<Int> = ArrayList()
                    val tag = "unseen-$accountId"
                    if (notifying.indexOfKey(accountId) >= 0) {
                        toRemove = notifying[accountId]
                    }
                    for (notification in notifications) {
                        val id = notification.extras.getLong("id", 0).toInt()
                        all.add(id)
                        if (toRemove.contains(id)) {
                            toRemove.remove(id)
                        } else if (id > 0) {
                            added.add(id)
                        }
                    }
                    for (notification in notifications) {
                        val id = notification.extras.getLong("id", 0).toInt()
                        if (id == 0 && added.size > 0 || added.contains(id)) {
                            notificationManager.notify(tag, id, notification)
                        }
                    }
                    removed.put(accountId, toRemove)
                    notifying.put(accountId, all)
                }

                // Cancel old per account
                for (i in 0 until removed.size()) {
                    val accountId = removed.keyAt(i)
                    val notifyRemove: List<Int> = removed[accountId]
                    val tag = "unseen-$accountId"
                    for (id in notifyRemove) {
                        notificationManager.cancel(tag, id)
                    }
                }
            }
        })
    }

    override fun onDestroy() {
        Log.i(Helper.TAG, "Service destroy")
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(serviceManager)
        serviceManager.maybeNetworkDisconnect(null)
        Widget.update(this, -1)
        stopForeground(true)
        val notificationManager = getNotificationManger(this)
        notificationManager.cancel(NOTIFICATION_SYNCHRONIZE)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.i(Helper.TAG, "Service command intent=$intent action=$action")
        startForeground(NOTIFICATION_SYNCHRONIZE, getNotificationService(null).build())
        super.onStartCommand(intent, flags, startId)

        if (action == null) {
            return START_STICKY
        }

        when (action) {
            "start" -> serviceManager.queue_start()
            "stop" -> serviceManager.queue_stop()
            "reload" -> serviceManager.queue_reload()
            "clear" -> onClearAction()
            else -> onFlagAction(action)
        }
        return START_STICKY
    }

    private fun getNotificationService(stats: TupleAccountStats?): Notification.Builder {
        var stats = stats
        if (stats == null) {
            stats = lastStats
        }
        if (stats == null) {
            stats = TupleAccountStats()
        }

        // Build pending intent
        val intent = Intent(this, ActivityView::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val pi = PendingIntent.getActivity(
            this, ActivityView.REQUEST_UNIFIED, intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification
        val builder = Helper.getNotificationBuilder(this, "service")
        builder.setSmallIcon(R.drawable.baseline_compare_arrows_white_24)
            .setContentTitle(
                resources.getQuantityString(
                    R.plurals.title_notification_synchronizing, stats.accounts, stats.accounts
                )
            )
            .setContentIntent(pi).setAutoCancel(false).setShowWhen(false)
            .setPriority(Notification.PRIORITY_MIN).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (stats.operations > 0) {
            builder.style = Notification.BigTextStyle().setSummaryText(
                resources.getQuantityString(
                    R.plurals.title_notification_operations, stats.operations, stats.operations
                )
            )
        }
        if (stats.unsent > 0) {
            builder.setContentText(
                resources.getQuantityString(
                    R.plurals.title_notification_unsent,
                    stats.unsent, stats.unsent
                )
            )
        }
        lastStats = stats
        return builder
    }

    private fun onClearAction() {
        object : SimpleTask<Unit>() {
            @Throws(Throwable::class)
            override fun onLoad(context: Context, args: Bundle) {
                DB.getInstance(context).message().ignoreAll()
            }
        }.load(this, Bundle())
    }

    private fun onFlagAction(actionFlag: String) {
        if (actionFlag.startsWith("seen:")
            || actionFlag.startsWith("trash:")
            || actionFlag.startsWith("ignored:")
        ) {
            val args = Bundle()
            args.putLong("id", actionFlag.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[1].toLong())
            args.putString("action", actionFlag.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()[0])

            object : SimpleTask<Unit>() {
                override fun onLoad(context: Context, args: Bundle) {
                    val id = args.getLong("id")
                    val action = args.getString("action")
                    val db = DB.getInstance(context)

                    try {
                        db.beginTransaction()
                        val message = db.message().getMessage(id)

                        when (action) {
                            "seen" -> {
                                db.message().setMessageUiSeen(message.id, true)
                                db.message().setMessageUiIgnored(message.id, true)
                                EntityOperation.queue(db, message, EntityOperation.SEEN, true)
                            }
                            "trash" -> {
                                db.message().setMessageUiHide(message.id, true)
                                val trash =
                                    db.folder().getFolderByType(message.account, EntityFolder.TRASH)
                                if (trash != null) {
                                    EntityOperation.queue(
                                        db,
                                        message,
                                        EntityOperation.MOVE,
                                        trash.id
                                    )
                                }
                            }
                            "ignored" -> {
                                db.message().setMessageUiIgnored(message.id, true)
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    EntityOperation.process(context)
                }
            }.load(this, args)
        }
    }

    /**
     * Update widget unseen message for all accounts
     *
     * @param messages - list of unseen messages
     */
    private fun setWidgetUnseen(messages: List<TupleNotification>) {
        Widget.update(this, messages.size)
    }

    /**
     * Get notification color by account or fallback app primary color
     *
     * @param accountColor - the account color
     * @return Integer
     */
    private fun getNotificationColor(accountColor: Int?): Int {
        return accountColor ?: ContextCompat.getColor(baseContext, R.color.colorPrimary)
    }

    /**
     * Get unique key for notifications
     *
     * @param accountName - the account name
     * @return String
     */
    private fun getNotificationKey(accountName: String): String {
        return BuildConfig.APPLICATION_ID + accountName
    }

    /**
     * Get notification public version
     *
     * @param accountName  - the account name
     * @param accountColor - the account color
     * @param size         - number of new messages
     * @return Notification.Builder
     */
    private fun getNotificationPublic(
        accountName: String, accountColor: Int,
        size: Int
    ): Notification.Builder {
        val channelId = "notification"

        // Build pending intent
        val view = Intent(this, ActivityView::class.java)
        view.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val piView = PendingIntent.getActivity(
            this, ActivityView.REQUEST_UNIFIED, view,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clear = Intent(this, ServiceSynchronize::class.java)
        clear.action = "clear"
        val piClear =
            PendingIntent.getService(this, PI_CLEAR, clear, PendingIntent.FLAG_UPDATE_CURRENT)
        val summaryText =
            resources.getQuantityString(R.plurals.title_notification_unseen, size, size)

        // Public notification
        val pbuilder = Helper.getNotificationBuilder(this, channelId)
        pbuilder.setSmallIcon(R.drawable.ic_mail_icon).setContentTitle(summaryText)
            .setContentText(accountName).setContentIntent(piView).setNumber(size).setShowWhen(true)
            .setColor(accountColor).setDeleteIntent(piClear)
            .setPriority(Notification.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PUBLIC)
        return pbuilder
    }

    /**
     * Get notification group per account
     *
     * @param accountName  - the account name
     * @param accountColor - the account color
     * @param messages     - account messages
     * @return Notification.Builder
     */
    private fun getNotificationGroup(
        accountName: String, accountColor: Int,
        messages: List<TupleNotification>
    ): Notification.Builder {
        // https://developer.android.com/training/notify-user/group
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val groupColor = getNotificationColor(accountColor)
        val groupKey = getNotificationKey(accountName)
        val channelId = "notification"
        val size = messages.size

        // Build pending intent
        val view = Intent(this, ActivityView::class.java)
        view.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val piView = PendingIntent.getActivity(
            this, ActivityView.REQUEST_UNIFIED, view,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clear = Intent(this, ServiceSynchronize::class.java)
        clear.action = "clear"
        val piClear =
            PendingIntent.getService(this, PI_CLEAR, clear, PendingIntent.FLAG_UPDATE_CURRENT)
        val summaryText =
            resources.getQuantityString(R.plurals.title_notification_unseen, size, size)

        // Summary notification
        val pbuilder = getNotificationPublic(accountName, groupColor, size)
        val gbuilder = Helper.getNotificationBuilder(this, channelId)
        gbuilder.setSmallIcon(R.drawable.ic_mail_icon).setContentTitle(summaryText)
            .setContentIntent(piView)
            .setNumber(messages.size).setShowWhen(true).setColor(groupColor)
            .setDeleteIntent(piClear)
            .setPriority(Notification.PRIORITY_DEFAULT).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setGroup(groupKey).setGroupSummary(true)
            .setPublicVersion(pbuilder.build())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            gbuilder.setSound(null)
        } else {
            gbuilder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && prefs.getBoolean("light", false)) {
            gbuilder.setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_LIGHTS)
            gbuilder.setLights(-0xff0100, 1000, 1000)
        }
        val df =
            SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.SHORT, SimpleDateFormat.SHORT)
        val sb = StringBuilder()
        for (message in messages) {
            sb.append("<strong>").append(MessageHelper.getFormattedAddresses(message.from, null))
                .append("</strong>")
            if (!TextUtils.isEmpty(message.subject)) {
                sb.append(": ").append(message.subject)
            }
            sb.append(" ")
                .append(df.format(Date(if (message.sent == null) message.received else message.sent)))
            sb.append("<br>")
        }
        val gstyle = Notification.BigTextStyle().bigText(Html.fromHtml(sb.toString()))
            .setSummaryText(accountName)
        gbuilder.style = gstyle
        return gbuilder
    }

    /**
     * Get public/summary and individual notifications per account
     *
     * @param messages - list of unseen notifications
     * @param account  - account information (name, color)
     * @return List<Notification>
    </Notification> */
    private fun getNotificationUnseen(
        messages: List<TupleNotification>,
        account: Pair<*, *>?
    ): List<Notification> {
        // https://developer.android.com/training/notify-user/group
        val notifications: MutableList<Notification> = ArrayList()
        val size = messages.size
        if (size == 0 || account == null) {
            return notifications
        }
        val accountName = account.first as String
        val accountColor = account.second as Int
        val groupColor = getNotificationColor(accountColor)
        val groupKey = getNotificationKey(accountName)
        val channelId = "notification"
        val gbuilder = getNotificationGroup(accountName, accountColor, messages)
        notifications.add(gbuilder.build())
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        for (message in messages) {
            val mArgs = Bundle()
            mArgs.putLong("id", message.id)
            val thread = Intent(this, ActivityView::class.java)
            thread.action = "thread:" + message.thread
            thread.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            thread.putExtra("account", message.account)
            val piContent = PendingIntent.getActivity(
                this, ActivityView.REQUEST_THREAD, thread,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            val ignored = Intent(this, ServiceSynchronize::class.java)
            ignored.action = "ignored:" + message.id
            val piDelete = PendingIntent.getService(
                this,
                PI_IGNORED,
                ignored,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            val seen = Intent(this, ServiceSynchronize::class.java)
            seen.action = "seen:" + message.id
            val piSeen =
                PendingIntent.getService(this, PI_SEEN, seen, PendingIntent.FLAG_UPDATE_CURRENT)
            val trash = Intent(this, ServiceSynchronize::class.java)
            trash.action = "trash:" + message.id
            val piTrash =
                PendingIntent.getService(this, PI_TRASH, trash, PendingIntent.FLAG_UPDATE_CURRENT)
            val actionSeen = Notification.Action.Builder(
                R.drawable.baseline_visibility_24, getString(R.string.title_seen), piSeen
            )
            val actionTrash = Notification.Action.Builder(
                R.drawable.baseline_delete_24, getString(R.string.title_trash), piTrash
            )
            val mbuilder = Helper.getNotificationBuilder(this, channelId)
            val mstyle = Notification.InboxStyle()
            mbuilder.addExtras(mArgs).setSmallIcon(R.drawable.ic_mail_icon)
                .setContentTitle(
                    MessageHelper.getFormattedAddresses(
                        message.from,
                        MessageHelper.ADDRESS_FULL
                    )
                ).setContentIntent(piContent)
                .setDeleteIntent(piDelete).setSound(uri).setColor(groupColor)
                .setWhen(if (message.sent == null) message.received else message.sent).setPriority(
                    Notification.PRIORITY_DEFAULT
                )
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setGroup(groupKey)
                .setGroupSummary(false).addAction(actionSeen.build()).addAction(actionTrash.build())
            if (!TextUtils.isEmpty(message.subject)) {
                mbuilder.setContentText(message.subject)
                mstyle.addLine(message.subject)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mbuilder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            }
            mstyle.setBigContentTitle(MessageHelper.getFormattedAddresses(message.from, null))
                .setSummaryText(accountName)
            mbuilder.style = mstyle
            notifications.add(mbuilder.build())
        }
        return notifications
    }

    private fun getNotificationError(action: String?, ex: Throwable): Notification.Builder {
        // Build pending intent
        val intent = Intent(this, ActivityView::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val pi = PendingIntent.getActivity(
            this, ActivityView.REQUEST_ERROR, intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build notification
        val builder = Helper.getNotificationBuilder(this, "error")
        builder.setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(String.format(getString(R.string.title_notification_failed), action))
            .setContentText(Helper.formatThrowable(ex)).setContentIntent(pi).setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(true).setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ERROR)
            .setVisibility(Notification.VISIBILITY_SECRET)
        builder.style = Notification.BigTextStyle().bigText(ex.toString())
        return builder
    }

    private fun reportError(account: String?, folder: String?, ex: Throwable) {
        // FolderClosedException: can happen when no connectivity

        // IllegalStateException:
        // - "This operation is not allowed on a closed folder"
        // - can happen when syncing message

        // ConnectionException
        // - failed to create new store connection (connectivity)

        // MailConnectException
        // - on connectity problems when connecting to store
        val action: String?
        action = if (TextUtils.isEmpty(account)) {
            folder
        } else if (TextUtils.isEmpty(folder)) {
            account
        } else {
            "$account/$folder"
        }
        EntityLog.log(this, action + " " + Helper.formatThrowable(ex))
        if (ex is SendFailedException) {
            val notificationManager = getNotificationManger(this)
            notificationManager.notify(action, 1, getNotificationError(action, ex).build())
        }
        if (BuildConfig.DEBUG && ex !is SendFailedException && ex !is MailConnectException
            && ex !is FolderClosedException && ex !is IllegalStateException
            && ex !is AuthenticationFailedException &&  // Also: Too many simultaneous connections
            ex !is StoreClosedException
            && !(ex is MessagingException && ex.cause is UnknownHostException)
            && !(ex is MessagingException && ex.cause is ConnectionException)
            && !(ex is MessagingException && ex.cause is SocketException)
            && !(ex is MessagingException && ex.cause is SocketTimeoutException)
            && !(ex is MessagingException && ex.cause is SSLException)
            && !(ex is MessagingException && "connection failure" == ex.message)
        ) {
            val notificationManager = getNotificationManger(this)
            notificationManager.notify(action, 1, getNotificationError(action, ex).build())
        }
    }

    @Throws(NoSuchProviderException::class)
    private fun monitorAccount(account: EntityAccount, state: ServiceState) {
        val powerManager = getPowerManager(baseContext)
        val wl0 = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            BuildConfig.APPLICATION_ID + ":account." + account.id + ".monitor"
        )
        try {
            wl0.acquire()
            val db = DB.getInstance(this)
            val executor = Executors.newSingleThreadExecutor(Helper.backgroundThreadFactory)
            var backoff = CONNECT_BACKOFF_START
            while (state.running) {
                EntityLog.log(this, account.name + " run")

                // Debug
                var debug =
                    PreferenceManager.getDefaultSharedPreferences(this).getBoolean("debug", false)
                debug = debug || BuildConfig.DEBUG
                System.setProperty("mail.socket.debug", java.lang.Boolean.toString(debug))

                // Create session
                val props = MessageHelper.getSessionProperties(account.auth_type, account.insecure)
                val isession = Session.getInstance(props, null)
                isession.debug = debug
                val istore =
                    isession.getStore(if (account.starttls) "imap" else "imaps") as IMAPStore
                val folders: MutableMap<EntityFolder, IMAPFolder> = HashMap()
                val syncs: MutableList<Thread> = ArrayList()
                val idlers: MutableList<Thread> = ArrayList()
                try {
                    // Listen for store events
                    istore.addStoreListener(object : StoreListener {
                        var wl = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            BuildConfig.APPLICATION_ID + ":account." + account.id + ".store"
                        )

                        override fun notification(e: StoreEvent) {
                            try {
                                wl.acquire()
                                Log.i(Helper.TAG, account.name + " event: " + e.message)
                                db.account().setAccountError(account.id, e.message)
                                state.thread!!.interrupt()
                                yieldWakelock()
                            } finally {
                                wl.release()
                            }
                        }
                    })

                    // Listen for folder events
                    istore.addFolderListener(object : FolderAdapter() {
                        var wl = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            BuildConfig.APPLICATION_ID + ":account." + account.id + ".folder"
                        )

                        override fun folderCreated(e: FolderEvent) {
                            try {
                                wl.acquire()
                                Log.i(Helper.TAG, "Folder created=" + e.folder.fullName)
                                state.thread!!.interrupt()
                                yieldWakelock()
                            } finally {
                                wl.release()
                            }
                        }

                        override fun folderRenamed(e: FolderEvent) {
                            try {
                                wl.acquire()
                                Log.i(Helper.TAG, "Folder renamed=" + e.folder)
                                val old = e.folder.fullName
                                val name = e.newFolder.fullName
                                val count = db.folder().renameFolder(account.id, old, name)
                                Log.i(Helper.TAG, "Renamed to $name count=$count")
                                state.thread!!.interrupt()
                                yieldWakelock()
                            } finally {
                                wl.release()
                            }
                        }

                        override fun folderDeleted(e: FolderEvent) {
                            try {
                                wl.acquire()
                                Log.i(Helper.TAG, "Folder deleted=" + e.folder.fullName)
                                state.thread!!.interrupt()
                                yieldWakelock()
                            } finally {
                                wl.release()
                            }
                        }
                    })

                    // Listen for connection events
                    istore.addConnectionListener(object : ConnectionAdapter() {
                        override fun opened(e: ConnectionEvent) {
                            Log.i(Helper.TAG, account.name + " opened")
                        }

                        override fun disconnected(e: ConnectionEvent) {
                            Log.e(Helper.TAG, account.name + " disconnected event")
                        }

                        override fun closed(e: ConnectionEvent) {
                            Log.e(Helper.TAG, account.name + " closed event")
                        }
                    })

                    // Initiate connection
                    Log.i(Helper.TAG, account.name + " connect")
                    for (folder in db.folder().getFolders(account.id)) {
                        db.folder().setFolderState(folder.id, null)
                    }
                    db.account().setAccountState(account.id, "connecting")
                    Helper.connect(this, istore, account)
                    val capIdle = istore.hasCapability("IDLE")
                    Log.i(Helper.TAG, account.name + " idle=" + capIdle)
                    db.account().setAccountState(account.id, "connected")
                    db.account().setAccountError(account.id, null)
                    EntityLog.log(this, account.name + " connected")

                    // Update folder list
                    synchronizeFolders(account, istore, state)

                    // Open folders
                    for (folder in db.folder().getFolders(account.id, true)) {
                        Log.i(Helper.TAG, account.name + " sync folder " + folder.name)
                        db.folder().setFolderState(folder.id, "connecting")
                        val ifolder = istore.getFolder(folder.name) as IMAPFolder
                        try {
                            ifolder.open(Folder.READ_WRITE)
                        } catch (ex: Throwable) {
                            db.folder().setFolderError(folder.id, Helper.formatThrowable(ex))
                            throw ex
                        }
                        folders[folder] = ifolder
                        db.folder().setFolderState(folder.id, "connected")
                        db.folder().setFolderError(folder.id, null)

                        // Synchronize folder
                        val sync = Thread(object : Runnable {
                            var wl = powerManager.newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                BuildConfig.APPLICATION_ID + ":account." + account.id + ".sync"
                            )

                            override fun run() {
                                try {
                                    wl.acquire()

                                    // Process pending operations
                                    processOperations(folder, isession, istore, ifolder)

                                    // Listen for new and deleted messages
                                    ifolder.addMessageCountListener(object : MessageCountAdapter() {
                                        override fun messagesAdded(e: MessageCountEvent) {
                                            synchronized(lock) {
                                                try {
                                                    wl.acquire()
                                                    Log.i(
                                                        Helper.TAG,
                                                        folder.name + " messages added"
                                                    )
                                                    val fp = FetchProfile()
                                                    fp.add(FetchProfile.Item.ENVELOPE)
                                                    fp.add(FetchProfile.Item.FLAGS)
                                                    fp.add(FetchProfile.Item.CONTENT_INFO) // body structure
                                                    fp.add(UIDFolder.FetchProfileItem.UID)
                                                    fp.add(IMAPFolder.FetchProfileItem.HEADERS)
                                                    fp.add(IMAPFolder.FetchProfileItem.MESSAGE)
                                                    fp.add(FetchProfile.Item.SIZE)
                                                    fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE)
                                                    ifolder.fetch(e.messages, fp)
                                                    for (imessage in e.messages) {
                                                        try {
                                                            var id: Long
                                                            try {
                                                                db.beginTransaction()
                                                                id = synchronizeMessage(
                                                                    this@ServiceSynchronize,
                                                                    folder,
                                                                    ifolder,
                                                                    imessage as IMAPMessage,
                                                                    false
                                                                )
                                                                db.setTransactionSuccessful()
                                                            } finally {
                                                                db.endTransaction()
                                                            }
                                                            downloadMessage(
                                                                this@ServiceSynchronize,
                                                                folder,
                                                                ifolder,
                                                                imessage as IMAPMessage,
                                                                id
                                                            )
                                                        } catch (ex: MessageRemovedException) {
                                                            Log.w(
                                                                Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                            )
                                                        } catch (ex: IOException) {
                                                            if (ex.cause is MessageRemovedException) {
                                                                Log.w(
                                                                    Helper.TAG,
                                                                    """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                                )
                                                            } else {
                                                                throw ex
                                                            }
                                                        }
                                                    }
                                                    EntityOperation.process(this@ServiceSynchronize) // download small
                                                    // attachments
                                                } catch (ex: Throwable) {
                                                    Log.e(
                                                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                    )
                                                    reportError(account.name, folder.name, ex)
                                                    db.folder().setFolderError(
                                                        folder.id,
                                                        Helper.formatThrowable(ex)
                                                    )
                                                    state.thread!!.interrupt()
                                                    yieldWakelock()
                                                } finally {
                                                    wl.release()
                                                }
                                            }
                                        }

                                        override fun messagesRemoved(e: MessageCountEvent) {
                                            synchronized(lock) {
                                                try {
                                                    wl.acquire()
                                                    Log.i(
                                                        Helper.TAG,
                                                        folder.name + " messages removed"
                                                    )
                                                    for (imessage in e.messages) {
                                                        try {
                                                            val uid = ifolder.getUID(imessage)
                                                            val db =
                                                                DB.getInstance(this@ServiceSynchronize)
                                                            val count = db.message()
                                                                .deleteMessage(folder.id, uid)
                                                            Log.i(
                                                                Helper.TAG,
                                                                "Deleted uid=$uid count=$count"
                                                            )
                                                        } catch (ex: MessageRemovedException) {
                                                            Log.w(
                                                                Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                            )
                                                        }
                                                    }
                                                } catch (ex: Throwable) {
                                                    Log.e(
                                                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                    )
                                                    reportError(account.name, folder.name, ex)
                                                    db.folder().setFolderError(
                                                        folder.id,
                                                        Helper.formatThrowable(ex)
                                                    )
                                                    state.thread!!.interrupt()
                                                } finally {
                                                    wl.release()
                                                }
                                            }
                                        }
                                    })

                                    // Fetch e-mail
                                    synchronizeMessages(account, folder, ifolder, state)

                                    // Flags (like "seen") at the remote could be
                                    // changed while synchronizing

                                    // Listen for changed messages
                                    ifolder.addMessageChangedListener { e ->
                                        synchronized(lock) {
                                            try {
                                                wl.acquire()
                                                try {
                                                    Log.i(
                                                        Helper.TAG,
                                                        folder.name + " message changed"
                                                    )
                                                    val fp = FetchProfile()
                                                    fp.add(UIDFolder.FetchProfileItem.UID)
                                                    fp.add(IMAPFolder.FetchProfileItem.FLAGS)
                                                    ifolder.fetch(arrayOf(e.message), fp)
                                                    val id: Long
                                                    try {
                                                        db.beginTransaction()
                                                        id = synchronizeMessage(
                                                            this@ServiceSynchronize,
                                                            folder,
                                                            ifolder,
                                                            e.message as IMAPMessage,
                                                            false
                                                        )
                                                        db.setTransactionSuccessful()
                                                    } finally {
                                                        db.endTransaction()
                                                    }
                                                    downloadMessage(
                                                        this@ServiceSynchronize,
                                                        folder,
                                                        ifolder,
                                                        e.message as IMAPMessage,
                                                        id
                                                    )
                                                } catch (ex: MessageRemovedException) {
                                                    Log.w(
                                                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                    )
                                                } catch (ex: IOException) {
                                                    if (ex.cause is MessageRemovedException) {
                                                        Log.w(
                                                            Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                        )
                                                    } else {
                                                        throw ex
                                                    }
                                                }
                                            } catch (ex: Throwable) {
                                                Log.e(
                                                    Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                                )
                                                reportError(account.name, folder.name, ex)
                                                db.folder().setFolderError(
                                                    folder.id,
                                                    Helper.formatThrowable(ex)
                                                )
                                                state.thread!!.interrupt()
                                                yieldWakelock()
                                            } finally {
                                                wl.release()
                                            }
                                        }
                                    }
                                } catch (ex: Throwable) {
                                    Log.e(
                                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                    )
                                    reportError(account.name, folder.name, ex)
                                    db.folder()
                                        .setFolderError(folder.id, Helper.formatThrowable(ex))
                                    state.thread!!.interrupt()
                                    yieldWakelock()
                                } finally {
                                    wl.release()
                                }
                            }
                        }, "sync." + folder.id)
                        sync.start()
                        syncs.add(sync)

                        // Idle folder
                        if (capIdle) {
                            val idler = Thread({
                                try {
                                    Log.i(Helper.TAG, folder.name + " start idle")
                                    while (state.running) {
                                        Log.i(Helper.TAG, folder.name + " do idle")
                                        ifolder.idle(false)
                                        // Log.i(Helper.TAG, folder.name + "
                                        // done idle");
                                    }
                                } catch (ignored: FolderClosedException) {
                                } catch (ex: Throwable) {
                                    Log.e(
                                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                                    )
                                    reportError(account.name, folder.name, ex)
                                    db.folder()
                                        .setFolderError(folder.id, Helper.formatThrowable(ex))
                                    state.thread!!.interrupt()
                                    yieldWakelock()
                                } finally {
                                    Log.i(Helper.TAG, folder.name + " end idle")
                                }
                            }, "idler." + folder.id)
                            idler.start()
                            idlers.add(idler)
                        }
                    }

                    // Successfully connected: reset back off time
                    backoff = CONNECT_BACKOFF_START

                    // Process folder actions
                    val processFolder: BroadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            executor.submit(object : Runnable {
                                var wl = powerManager.newWakeLock(
                                    PowerManager.PARTIAL_WAKE_LOCK,
                                    BuildConfig.APPLICATION_ID + ":account." + account.id + ".process"
                                )

                                override fun run() {
                                    val fid = intent.getLongExtra("folder", -1)
                                    try {
                                        wl.acquire()
                                        Log.i(Helper.TAG, "Process folder=$fid intent=$intent")

                                        // Get folder
                                        var folder: EntityFolder? = null
                                        var ifolder: IMAPFolder? = null
                                        for (f in folders.keys) {
                                            if (f.id == fid) {
                                                folder = f
                                                ifolder = folders[f]
                                                break
                                            }
                                        }
                                        val shouldClose = folder == null
                                        try {
                                            if (folder == null) {
                                                folder = db.folder().getFolder(fid)
                                            }
                                            Log.i(
                                                Helper.TAG,
                                                folder!!.name + " run " + if (shouldClose) "offline" else "online"
                                            )
                                            if (ifolder == null) {
                                                // Prevent unnecessary folder
                                                // connections
                                                if (ACTION_PROCESS_OPERATIONS == intent.action) {
                                                    if (db.operation()
                                                            .getOperationCount(fid, null) == 0
                                                    ) {
                                                        return
                                                    }
                                                }
                                                db.folder().setFolderState(folder.id, "connecting")
                                                ifolder =
                                                    istore.getFolder(folder.name) as IMAPFolder
                                                ifolder!!.open(Folder.READ_WRITE)
                                                db.folder().setFolderState(folder.id, "connected")
                                                db.folder().setFolderError(folder.id, null)
                                            }
                                            if (ACTION_PROCESS_OPERATIONS == intent.action) {
                                                processOperations(folder, isession, istore, ifolder)
                                            } else if (ACTION_SYNCHRONIZE_FOLDER == intent.action) {
                                                processOperations(folder, isession, istore, ifolder)
                                                synchronizeMessages(account, folder, ifolder, state)
                                            }
                                        } catch (ex: Throwable) {
                                            Log.e(
                                                Helper.TAG, """${folder!!.name} $ex ${Log.getStackTraceString(ex)}"""
                                            )
                                            reportError(account.name, folder.name, ex)
                                            db.folder().setFolderError(
                                                folder.id,
                                                Helper.formatThrowable(ex)
                                            )
                                        } finally {
                                            if (shouldClose) {
                                                if (ifolder != null && ifolder.isOpen) {
                                                    db.folder()
                                                        .setFolderState(folder!!.id, "closing")
                                                    try {
                                                        ifolder.close(false)
                                                    } catch (ex: MessagingException) {
                                                        Log.w(
                                                            Helper.TAG, """${folder.name} $ex ${Log.getStackTraceString(ex)}"""
                                                        )
                                                    }
                                                }
                                                db.folder().setFolderState(folder!!.id, null)
                                            }
                                        }
                                    } finally {
                                        wl.release()
                                    }
                                }
                            })
                        }
                    }

                    // Listen for folder operations
                    val f = IntentFilter()
                    f.addAction(ACTION_SYNCHRONIZE_FOLDER)
                    f.addAction(ACTION_PROCESS_OPERATIONS)
                    f.addDataType("account/" + account.id)
                    val lbm = LocalBroadcastManager.getInstance(this@ServiceSynchronize)
                    lbm.registerReceiver(processFolder, f)
                    for (folder in folders.keys) {
                        if (db.operation().getOperationCount(folder.id, null) > 0) {
                            val intent = Intent()
                            intent.type = "account/" + account.id
                            intent.action = ACTION_PROCESS_OPERATIONS
                            intent.putExtra("folder", folder.id)
                            lbm.sendBroadcast(intent)
                        }
                    }

                    // Keep alive alarm receiver
                    val alarm: BroadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            // Receiver runs on main thread
                            // Receiver has a wake lock for ~10 seconds
                            EntityLog.log(
                                context,
                                account.name + " keep alive wake lock=" + wl0.isHeld
                            )
                            state.thread!!.interrupt()
                            yieldWakelock()
                        }
                    }
                    val id = BuildConfig.APPLICATION_ID + ".POLL." + account.id
                    val pi = PendingIntent.getBroadcast(this@ServiceSynchronize, 0, Intent(id),
                        PendingIntent.FLAG_IMMUTABLE)
                    registerReceiver(alarm, IntentFilter(id))

                    // Keep alive
                    val alarmManager = getAlarmManager(this)
                    try {
                        while (state.running) {
                            // Schedule keep alive alarm
                            EntityLog.log(this, account.name + " wait=" + account.poll_interval)
                            setAndAllowWhileIdle(
                                alarmManager, AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + account.poll_interval * 60 * 1000L, pi
                            )
                            try {
                                wl0.release()
                                state.semaphore.acquire()
                            } catch (ex: InterruptedException) {
                                EntityLog.log(
                                    this,
                                    account.name + " waited running=" + state.running
                                )
                            } finally {
                                wl0.acquire()
                            }
                            if (state.running) {
                                if (!istore.isConnected) {
                                    throw StoreClosedException(istore)
                                }
                                for (folder in folders.keys) {
                                    if (capIdle) {
                                        if (!folders[folder]!!.isOpen) {
                                            throw FolderClosedException(folders[folder])
                                        }
                                    } else {
                                        synchronizeMessages(account, folder, folders[folder], state)
                                    }
                                }
                            }
                        }
                    } finally {
                        // Cleanup
                        alarmManager.cancel(pi)
                        unregisterReceiver(alarm)
                        lbm.unregisterReceiver(processFolder)
                    }
                    Log.i(Helper.TAG, account.name + " done running=" + state.running)
                } catch (ex: Throwable) {
                    Log.e(
                        Helper.TAG, """${account.name} $ex
${Log.getStackTraceString(ex)}"""
                    )
                    reportError(account.name, null, ex)
                    db.account().setAccountError(account.id, Helper.formatThrowable(ex))
                } finally {
                    EntityLog.log(this, account.name + " closing")
                    db.account().setAccountState(account.id, "closing")
                    for (folder in folders.keys) {
                        db.folder().setFolderState(folder.id, "closing")
                    }

                    // Stop syncs
                    for (sync in syncs) {
                        sync.interrupt()
                        join(sync)
                    }

                    // Close store
                    try {
                        val t = Thread {
                            try {
                                EntityLog.log(
                                    this@ServiceSynchronize,
                                    account.name + " store closing"
                                )
                                istore.close()
                                EntityLog.log(
                                    this@ServiceSynchronize,
                                    account.name + " store closed"
                                )
                            } catch (ex: Throwable) {
                                Log.w(
                                    Helper.TAG, """${account.name} $ex
${Log.getStackTraceString(ex)}"""
                                )
                            }
                        }
                        t.start()
                        try {
                            t.join(MessageHelper.NETWORK_TIMEOUT.toLong())
                            if (t.isAlive) {
                                Log.w(Helper.TAG, account.name + " Close timeout")
                            }
                        } catch (ex: InterruptedException) {
                            Log.w(Helper.TAG, account.name + " close wait " + ex.toString())
                            t.interrupt()
                        }
                    } finally {
                        EntityLog.log(this, account.name + " closed")
                        db.account().setAccountState(account.id, null)
                        for (folder in folders.keys) {
                            db.folder().setFolderState(folder.id, null)
                        }
                    }

                    // Stop idlers
                    for (idler in idlers) {
                        idler.interrupt()
                        join(idler)
                    }
                }
                if (state.running) {
                    try {
                        EntityLog.log(this, account.name + " backoff=" + backoff)
                        Thread.sleep(backoff * 1000L)
                        if (backoff < CONNECT_BACKOFF_MAX) {
                            backoff *= 2
                        }
                    } catch (ex: InterruptedException) {
                        Log.w(Helper.TAG, account.name + " backoff " + ex.toString())
                    }
                }
            }
        } finally {
            EntityLog.log(this, account.name + " stopped")
            wl0.release()
        }
    }

    @Throws(MessagingException::class, JSONException::class, IOException::class)
    private fun processOperations(
        folder: EntityFolder?,
        isession: Session?,
        istore: IMAPStore?,
        ifolder: IMAPFolder?
    ) {
        synchronized(lock) {
            try {
                Log.i(Helper.TAG, folder!!.name + " start process")
                val db = DB.getInstance(this)
                val ops = db.operation().getOperationsByFolder(
                    folder.id
                )
                Log.i(Helper.TAG, folder.name + " pending operations=" + ops.size)
                for (op in ops) {
                    try {
                        Log.i(
                            Helper.TAG,
                            folder.name + " start op=" + op.id + "/" + op.name + " msg=" + op.message + " args=" + op.args
                        )
                        val message = db.message().getMessage(op.message)
                        try {
                            if (message == null) {
                                throw MessageRemovedException()
                            }
                            db.message().setMessageError(message.id, null)
                            require(!(message.uid == null && (EntityOperation.SEEN == op.name || EntityOperation.DELETE == op.name || EntityOperation.MOVE == op.name || EntityOperation.HEADERS == op.name))) { op.name + " without uid " + op.args }
                            val jargs = JSONArray(op.args)
                            if (EntityOperation.SEEN == op.name) {
                                doSeen(folder, ifolder, message, jargs, db)
                            } else if (EntityOperation.FLAG == op.name) {
                                doFlag(folder, ifolder, message, jargs, db)
                            } else if (EntityOperation.ADD == op.name) {
                                doAdd(folder, isession, ifolder, message, jargs, db)
                            } else if (EntityOperation.MOVE == op.name) {
                                doMove(folder, isession, istore, ifolder, message, jargs, db)
                            } else if (EntityOperation.DELETE == op.name) {
                                doDelete(folder, ifolder, message, jargs, db)
                            } else if (EntityOperation.SEND == op.name) {
                                doSend(message, db)
                            } else if (EntityOperation.HEADERS == op.name) {
                                doHeaders(folder, ifolder, message, db)
                            } else if (EntityOperation.BODY == op.name) {
                                doBody(folder, ifolder, message, db)
                            } else if (EntityOperation.ATTACHMENT == op.name) {
                                doAttachment(folder, op, ifolder, message, jargs, db)
                            } else {
                                throw MessagingException("Unknown operation name=" + op.name)
                            }

                            // Operation succeeded
                            db.operation().deleteOperation(op.id)
                        } catch (ex: Throwable) {
                            // TODO: SMTP response codes: https://www.ietf.org/rfc/rfc821.txt
                            if (ex is SendFailedException) {
                                reportError(null, folder.name, ex)
                            }
                            if (message != null) {
                                db.message().setMessageError(message.id, Helper.formatThrowable(ex))
                            }
                            if (ex is MessageRemovedException || ex is FolderNotFoundException
                                || ex is SendFailedException
                            ) {
                                Log.w(
                                    Helper.TAG, """
     Unrecoverable $ex
     ${Log.getStackTraceString(ex)}
     """.trimIndent()
                                )

                                // There is no use in repeating
                                db.operation().deleteOperation(op.id)
                                continue
                            } else if (ex is MessagingException) {
                                // Socket timeout is a recoverable condition (send message)
                                if (ex.cause is SocketTimeoutException) {
                                    Log.w(
                                        Helper.TAG, """
     Recoverable $ex
     ${Log.getStackTraceString(ex)}
     """.trimIndent()
                                    )
                                    // No need to inform user
                                    return
                                }
                            }
                            throw ex
                        }
                    } finally {
                        Log.i(Helper.TAG, folder.name + " end op=" + op.id + "/" + op.name)
                    }
                }
            } finally {
                Log.i(Helper.TAG, folder!!.name + " end process")
            }
        }
    }

    @Throws(MessagingException::class, JSONException::class)
    private fun doSeen(
        folder: EntityFolder?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        jargs: JSONArray,
        db: DB
    ) {
        // Mark message (un)seen
        val seen = jargs.getBoolean(0)
        if (message.seen == seen) {
            return
        }
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        imessage.setFlag(Flags.Flag.SEEN, seen)
        db.message().setMessageSeen(message.id, seen)
    }

    @Throws(MessagingException::class, JSONException::class)
    private fun doFlag(
        folder: EntityFolder?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        jargs: JSONArray,
        db: DB
    ) {
        // Star/unstar message
        val flagged = jargs.getBoolean(0)
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        imessage.setFlag(Flags.Flag.FLAGGED, flagged)
        db.message().setMessageFlagged(message.id, flagged)
    }

    @Throws(MessagingException::class, JSONException::class, IOException::class)
    private fun doAdd(
        folder: EntityFolder?,
        isession: Session?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        jargs: JSONArray,
        db: DB
    ) {
        // Append message
        val attachments = db.attachment().getAttachments(message.id)
        val imessage: MimeMessage = MessageHelper.from(this, message, null, attachments, isession)
        val uid = ifolder!!.appendUIDMessages(arrayOf<Message>(imessage))
        db.message().setMessageUid(message.id, uid[0].uid)
        Log.i(Helper.TAG, "Appended uid=" + uid[0].uid)
        if (message.uid != null) {
            val iprev = ifolder.getMessageByUID(message.uid)
            if (iprev != null) {
                Log.i(Helper.TAG, "Deleting existing uid=" + message.uid)
                iprev.setFlag(Flags.Flag.DELETED, true)
                ifolder.expunge()
            }
        }
    }

    @Throws(JSONException::class, MessagingException::class, IOException::class)
    private fun doMove(
        folder: EntityFolder?,
        isession: Session?,
        istore: IMAPStore?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        jargs: JSONArray,
        db: DB
    ) {
        // Move message
        val id = jargs.getLong(0)
        val target = db.folder().getFolder(id) ?: throw FolderNotFoundException()

        // Get message
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        if (istore!!.hasCapability("MOVE")) {
            val itarget = istore.getFolder(target.name)
            ifolder.moveMessages(arrayOf(imessage), itarget)
        } else {
            Log.w(Helper.TAG, "MOVE by DELETE/APPEND")
            val attachments = db.attachment().getAttachments(message.id)
            if (EntityFolder.ARCHIVE != folder!!.type) {
                imessage.setFlag(Flags.Flag.DELETED, true)
                ifolder.expunge()
            }
            val icopy = MessageHelper.from(this, message, null, attachments, isession)
            val itarget = istore.getFolder(target.name)
            itarget.appendMessages(arrayOf<Message>(icopy))
        }
    }

    @Throws(MessagingException::class, JSONException::class)
    private fun doDelete(
        folder: EntityFolder?, ifolder: IMAPFolder?, message: EntityMessage,
        jargs: JSONArray, db: DB
    ) {
        // Delete message
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        imessage.setFlag(Flags.Flag.DELETED, true)
        ifolder.expunge()
        db.message().deleteMessage(message.id)
    }

    @Throws(MessagingException::class, IOException::class)
    private fun doSend(message: EntityMessage, db: DB) {
        // Send message
        val ident = db.identity().getIdentity(message.identity)
        if (!ident.synchronize) {
            // Message will remain in outbox
            return
        }

        // Create session
        val props = MessageHelper.getSessionProperties(ident.auth_type, ident.insecure)
        val isession = Session.getInstance(props, null)

        // Create message
        val imessage: MimeMessage
        val reply =
            if (message.replying == null) null else db.message().getMessage(message.replying)
        val attachments = db.attachment().getAttachments(message.id)
        imessage = MessageHelper.from(this, message, reply, attachments, isession)
        if (ident.replyto != null) {
            imessage.setReplyTo(arrayOf<Address>(InternetAddress(ident.replyto)))
        }

        // Create transport
        // TODO: cache transport?
        val itransport = isession.getTransport(if (ident.starttls) "smtp" else "smtps")
        try {
            // Connect transport
            db.identity().setIdentityState(ident.id, "connecting")
            try {
                itransport.connect(ident.host, ident.port, ident.user, ident.password)
            } catch (ex: AuthenticationFailedException) {
                if (ident.auth_type == Helper.AUTH_TYPE_GMAIL) {
                    val account = db.account().getAccount(ident.account)
                    ident.password =
                        Helper.refreshToken(this, "com.google", ident.user, account.password)
                    DB.getInstance(this).identity().setIdentityPassword(ident.id, ident.password)
                    itransport.connect(ident.host, ident.port, ident.user, ident.password)
                } else {
                    throw ex
                }
            }
            db.identity().setIdentityState(ident.id, "connected")
            db.identity().setIdentityError(ident.id, null)

            // Send message
            val to = imessage.getAllRecipients()
            itransport.sendMessage(imessage, to)
            Log.i(
                Helper.TAG,
                "Sent via " + ident.host + "/" + ident.user + " to " + TextUtils.join(", ", to)
            )
            try {
                db.beginTransaction()

                // Mark message as sent
                // - will be moved to sent folder by synchronize message later
                message.sent = imessage.getSentDate().time
                message.seen = true
                message.ui_seen = true
                db.message().updateMessage(message)
                if (ident.store_sent) {
                    val sent = db.folder().getFolderByType(ident.account, EntityFolder.SENT)
                    if (sent != null) {
                        message.folder = sent.id
                        message.uid = null
                        db.message().updateMessage(message)
                        Log.i(Helper.TAG, "Appending sent msgid=" + message.msgid)
                        EntityOperation.queue(
                            db,
                            message,
                            EntityOperation.ADD
                        ) // Could already exist
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            EntityOperation.process(this)
        } catch (ex: MessagingException) {
            db.identity().setIdentityError(ident.id, Helper.formatThrowable(ex))
            throw ex
        } finally {
            try {
                itransport.close()
            } finally {
                db.identity().setIdentityState(ident.id, null)
            }
        }
    }

    @Throws(MessagingException::class)
    private fun doHeaders(
        folder: EntityFolder?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        db: DB
    ) {
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        val headers = imessage.allHeaders
        val sb = StringBuilder()
        while (headers.hasMoreElements()) {
            val header = headers.nextElement()
            sb.append(header.name).append(": ").append(header.value).append("\n")
        }
        db.message().setMessageHeaders(message.id, sb.toString())
    }

    @Throws(MessagingException::class, IOException::class)
    private fun doBody(
        folder: EntityFolder?,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        db: DB
    ) {
        // Download message body
        if (message.content) {
            return
        }

        // Get message
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()
        val helper = MessageHelper(imessage as MimeMessage)
        message.write(this, helper.html)
        db.message().setMessageContent(message.id, true)
    }

    @Throws(JSONException::class, MessagingException::class, IOException::class)
    private fun doAttachment(
        folder: EntityFolder?,
        op: EntityOperation,
        ifolder: IMAPFolder?,
        message: EntityMessage,
        jargs: JSONArray,
        db: DB
    ) {
        // Download attachment
        val sequence = jargs.getInt(0)

        // Get attachment
        val attachment = db.attachment().getAttachment(op.message, sequence)
        if (attachment.available) {
            return
        }

        // Get message
        val imessage = ifolder!!.getMessageByUID(message.uid) ?: throw MessageRemovedException()

        // Download attachment
        val helper = MessageHelper(imessage as MimeMessage)
        val a = helper.attachments[sequence - 1]
        attachment.part = a.part
        attachment.download(this, db)
    }

    @Throws(MessagingException::class)
    private fun synchronizeFolders(account: EntityAccount, istore: IMAPStore, state: ServiceState) {
        val db = DB.getInstance(this)
        try {
            db.beginTransaction()
            Log.v(Helper.TAG, "Start sync folders account=" + account.name)
            val names: MutableList<String> = ArrayList()
            for (folder in db.folder().getUserFolders(account.id)) {
                names.add(folder.name)
            }
            Log.i(Helper.TAG, "Local folder count=" + names.size)
            val ifolders = istore.defaultFolder.list("*")
            Log.i(Helper.TAG, "Remote folder count=" + ifolders.size)
            for (ifolder in ifolders) {
                var selectable = true
                val attrs = (ifolder as IMAPFolder).attributes
                for (attr in attrs) {
                    if ("\\Noselect" == attr) {
                        selectable = false
                    }
                }
                if (selectable) {
                    var folder = db.folder().getFolderByName(account.id, ifolder.getFullName())
                    if (folder == null) {
                        folder = EntityFolder()
                        folder.account = account.id
                        folder.name = ifolder.getFullName()
                        folder.type = EntityFolder.USER
                        folder.synchronize = false
                        folder.after = EntityFolder.DEFAULT_USER_SYNC
                        db.folder().insertFolder(folder)
                        Log.i(Helper.TAG, folder.name + " added")
                    } else {
                        names.remove(folder.name)
                        Log.i(Helper.TAG, folder.name + " exists")
                    }
                }
            }
            Log.i(Helper.TAG, "Delete local folder=" + names.size)
            for (name in names) {
                db.folder().deleteFolder(account.id, name)
                Log.i(Helper.TAG, "$name deleted")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            Log.v(Helper.TAG, "End sync folder")
        }
    }

    @Throws(MessagingException::class, IOException::class)
    private fun synchronizeMessages(
        account: EntityAccount, folder: EntityFolder?, ifolder: IMAPFolder?,
        state: ServiceState
    ) {
        val db = DB.getInstance(this)
        try {
            Log.v(Helper.TAG, folder!!.name + " start sync after=" + folder.after)
            db.folder().setFolderState(folder.id, "syncing")

            // Get reference times
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, -folder.after)
            cal[Calendar.HOUR_OF_DAY] = 0
            cal[Calendar.MINUTE] = 0
            cal[Calendar.SECOND] = 0
            cal[Calendar.MILLISECOND] = 0
            var ago = cal.timeInMillis
            if (ago < 0) {
                ago = 0
            }
            Log.i(Helper.TAG, folder.name + " ago=" + Date(ago))

            // Delete old local messages
            val old = db.message().deleteMessagesBefore(folder.id, ago)
            Log.i(Helper.TAG, folder.name + " local old=" + old)

            // Get list of local uids
            val uids = db.message().getUids(folder.id, ago)
            Log.i(Helper.TAG, folder.name + " local count=" + uids.size)

            // Reduce list of local uids
            val search = SystemClock.elapsedRealtime()
            val imessages = ifolder!!.search(ReceivedDateTerm(ComparisonTerm.GE, Date(ago)))
            Log.i(
                Helper.TAG, folder.name + " remote count=" + imessages.size + " search="
                        + (SystemClock.elapsedRealtime() - search) + " ms"
            )
            val fp = FetchProfile()
            fp.add(UIDFolder.FetchProfileItem.UID)
            fp.add(FetchProfile.Item.FLAGS)
            ifolder.fetch(imessages, fp)
            val fetch = SystemClock.elapsedRealtime()
            Log.i(
                Helper.TAG,
                folder.name + " remote fetched=" + (SystemClock.elapsedRealtime() - fetch) + " ms"
            )
            for (imessage in imessages) {
                if (!state.running) {
                    return
                }
                try {
                    uids.remove(ifolder.getUID(imessage))
                } catch (ex: MessageRemovedException) {
                    Log.w(
                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                    )
                } catch (ex: Throwable) {
                    Log.e(
                        Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                    )
                    reportError(account.name, folder.name, ex)
                    db.folder().setFolderError(folder.id, Helper.formatThrowable(ex))
                }
            }

            // Delete local messages not at remote
            Log.i(Helper.TAG, folder.name + " delete=" + uids.size)
            for (uid in uids) {
                val count = db.message().deleteMessage(folder.id, uid)
                Log.i(Helper.TAG, folder.name + " delete local uid=" + uid + " count=" + count)
            }
            fp.add(FetchProfile.Item.ENVELOPE)
            // fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.CONTENT_INFO) // body structure
            // fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add(IMAPFolder.FetchProfileItem.HEADERS)
            // fp.add(IMAPFolder.FetchProfileItem.MESSAGE);
            fp.add(FetchProfile.Item.SIZE)
            fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE)

            // Add/update local messages
            val ids = arrayOfNulls<Long>(imessages.size)
            Log.i(Helper.TAG, folder.name + " add=" + imessages.size)
            run {
                var i = imessages.size - 1
                while (i >= 0) {
                    val from = Math.max(0, i - SYNC_BATCH_SIZE + 1)
                    // Log.i(Helper.TAG, folder.name + " update " + from + " .. " + i);
                    val isub = Arrays.copyOfRange(imessages, from, i + 1)

                    // Full fetch new/changed messages only
                    val full: MutableList<Message> = ArrayList()
                    for (imessage in isub) {
                        val uid = ifolder.getUID(imessage)
                        val message = db.message().getMessageByUid(folder.id, uid, false)
                        if (message == null) {
                            full.add(imessage)
                        }
                    }
                    if (full.size > 0) {
                        val headers = SystemClock.elapsedRealtime()
                        ifolder.fetch(full.toTypedArray(), fp)
                        Log.i(
                            Helper.TAG, folder.name + " fetched headers=" + full.size + " "
                                    + (SystemClock.elapsedRealtime() - headers) + " ms"
                        )
                    }
                    for (j in isub.indices.reversed()) {
                        try {
                            db.beginTransaction()
                            ids[from + j] = synchronizeMessage(
                                this,
                                folder,
                                ifolder,
                                isub[j] as IMAPMessage,
                                false
                            )
                            db.setTransactionSuccessful()
                            Thread.sleep(20)
                        } catch (ex: MessageRemovedException) {
                            Log.w(
                                Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                            )
                        } catch (ex: FolderClosedException) {
                            throw ex
                        } catch (ex: FolderClosedIOException) {
                            throw ex
                        } catch (ex: Throwable) {
                            Log.e(
                                Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                            )
                        } finally {
                            db.endTransaction()
                            // Reduce memory usage
                            (isub[j] as IMAPMessage).invalidateHeaders()
                        }
                    }
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {
                    }
                    i -= SYNC_BATCH_SIZE
                }
            }
            db.folder().setFolderState(folder.id, "downloading")

            // fp.add(IMAPFolder.FetchProfileItem.MESSAGE);

            // Download messages/attachments
            Log.i(Helper.TAG, folder.name + " download=" + imessages.size)
            var i = imessages.size - 1
            while (i >= 0) {
                val from = Math.max(0, i - DOWNLOAD_BATCH_SIZE + 1)
                // Log.i(Helper.TAG, folder.name + " download " + from + " .. " + i);
                val isub = Arrays.copyOfRange(imessages, from, i + 1)
                // Fetch on demand
                for (j in isub.indices.reversed()) {
                    try {
                        // Log.i(Helper.TAG, folder.name + " download index=" + (from + j) + " id="
                        // + ids[from + j]);
                        if (ids[from + j] != null) {
                            downloadMessage(
                                this,
                                folder,
                                ifolder,
                                isub[j] as IMAPMessage,
                                ids[from + j]!!
                            )
                            Thread.sleep(20)
                        }
                    } catch (ex: FolderClosedException) {
                        throw ex
                    } catch (ex: FolderClosedIOException) {
                        throw ex
                    } catch (ex: Throwable) {
                        Log.e(
                            Helper.TAG, """${folder.name} $ex
${Log.getStackTraceString(ex)}"""
                        )
                    } finally {
                        // Free memory
                        (isub[j] as IMAPMessage).invalidateHeaders()
                    }
                }
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                }
                i -= DOWNLOAD_BATCH_SIZE
            }
        } finally {
            Log.v(Helper.TAG, folder!!.name + " end sync")
            db.folder()
                .setFolderState(folder.id, if (ifolder!!.isOpen) "connected" else "disconnected")
        }
    }

    private inner class ServiceManager : NetworkCallback() {
        private var state: ServiceState? = null
        private var running = false
        private var lastLost: Long = 0
        private var outbox: EntityFolder? = null
        private val lifecycle = Executors.newSingleThreadExecutor(Helper.backgroundThreadFactory)
        private val executor = Executors.newSingleThreadExecutor(Helper.backgroundThreadFactory)
        override fun onAvailable(network: Network) {
            val connectivityManager = getConnectivityManager(this@ServiceSynchronize)
            val ni = connectivityManager.getNetworkInfo(network)
            EntityLog.log(
                this@ServiceSynchronize,
                "Network available $network running=$running $ni"
            )
            if (!running) {
                running = true
                lifecycle.submit {
                    Log.i(Helper.TAG, "Starting service")
                    start()
                }
            }
        }

        override fun onLost(network: Network) {
            EntityLog.log(this@ServiceSynchronize, "Network lost $network running=$running")

            if (!running) {
                return
            }

            val connectivityManager = getConnectivityManager(this@ServiceSynchronize)
            var ani: NetworkInfo? = null

            try {
                ani = connectivityManager.activeNetworkInfo
            } catch (err: RuntimeException) {
                EntityLog.log(
                    this@ServiceSynchronize,
                    String.format("No Network information: ", err)
                )
            }

            maybeNetworkDisconnect(ani)
        }

        fun maybeNetworkDisconnect(ani: NetworkInfo?) {
            if (!running) {
                return
            }

            EntityLog.log(
                this@ServiceSynchronize,
                String.format("Network active=%s", ani)
            )
            if (ani == null || !ani.isConnected) {
                EntityLog.log(this@ServiceSynchronize, "Network disconnected=$ani")
                running = false
                lastLost = Date().time
                lifecycle.submit { stop() }
            }
        }

        private fun start() {
            EntityLog.log(this@ServiceSynchronize, "Main start")
            state = ServiceState()
            state!!.thread = Thread(object : Runnable {
                private val threadState: MutableList<ServiceState> = ArrayList()
                override fun run() {
                    val powerManager = getPowerManager(this@ServiceSynchronize)
                    val wl = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        BuildConfig.APPLICATION_ID + ":start"
                    )
                    try {
                        wl.acquire()
                        val db = DB.getInstance(this@ServiceSynchronize)
                        outbox = db.folder().outbox
                        if (outbox == null) {
                            EntityLog.log(this@ServiceSynchronize, "No outbox, halt")
                            stopSelf()
                            return
                        }
                        val accounts = db.account().getAccounts(true)
                        if (accounts.size == 0) {
                            EntityLog.log(this@ServiceSynchronize, "No accounts, halt")
                            stopSelf()
                            return
                        }
                        val ago = Date().time - lastLost
                        if (ago < RECONNECT_BACKOFF) {
                            try {
                                val backoff = RECONNECT_BACKOFF - ago
                                EntityLog.log(
                                    this@ServiceSynchronize,
                                    "Main backoff=" + backoff / 1000
                                )
                                Thread.sleep(backoff)
                            } catch (ex: InterruptedException) {
                                Log.w(Helper.TAG, "main backoff $ex")
                                return
                            }
                        }

                        // Start monitoring outbox
                        val f = IntentFilter()
                        f.addAction(ACTION_SYNCHRONIZE_FOLDER)
                        f.addAction(ACTION_PROCESS_OPERATIONS)
                        f.addDataType("account/outbox")
                        val lbm = LocalBroadcastManager.getInstance(this@ServiceSynchronize)
                        lbm.registerReceiver(outboxReceiver, f)
                        db.folder().setFolderState(outbox!!.id, "connected")
                        db.folder().setFolderError(outbox!!.id, null)
                        lbm.sendBroadcast(
                            Intent(ACTION_PROCESS_OPERATIONS).setType("account/outbox")
                                .putExtra("folder", outbox!!.id)
                        )

                        // Start monitoring accounts
                        for (account in accounts) {
                            Log.i(Helper.TAG, account.host + "/" + account.user + " run")
                            val astate: ServiceState = ServiceState()
                            astate.thread = Thread({
                                try {
                                    monitorAccount(account, astate)
                                } catch (ex: Throwable) {
                                    // Fall-safe
                                    Log.e(
                                        Helper.TAG, """
     $ex
     ${Log.getStackTraceString(ex)}
     """.trimIndent()
                                    )
                                }
                            }, "sync.account." + account.id)
                            astate.thread!!.start()
                            threadState.add(astate)
                        }
                        EntityLog.log(this@ServiceSynchronize, "Main started")
                        try {
                            yieldWakelock()
                            wl.release()
                            state!!.semaphore.acquire()
                        } catch (ex: InterruptedException) {
                            Log.w(Helper.TAG, "main wait $ex")
                        } finally {
                            wl.acquire()
                        }

                        // Stop monitoring accounts
                        for (astate in threadState) {
                            astate.running = false
                            astate.semaphore.release()
                            join(astate.thread)
                        }
                        threadState.clear()

                        // Stop monitoring outbox
                        lbm.unregisterReceiver(outboxReceiver)
                        Log.i(Helper.TAG, outbox!!.name + " unlisten operations")
                        db.folder().setFolderState(outbox!!.id, null)
                        EntityLog.log(this@ServiceSynchronize, "Main exited")
                    } catch (ex: Throwable) {
                        // Fail-safe
                        Log.e(
                            Helper.TAG, """
     $ex
     ${Log.getStackTraceString(ex)}
     """.trimIndent()
                        )
                    } finally {
                        wl.release()
                        EntityLog.log(this@ServiceSynchronize, "Start wake lock=" + wl.isHeld)
                    }
                }
            }, "sync.main")
            state!!.thread!!.priority = Process.THREAD_PRIORITY_BACKGROUND // will be inherited
            state!!.thread!!.start()
            yieldWakelock()
        }

        private fun stop() {
            val powerManager = getPowerManager(this@ServiceSynchronize)
            val wl = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                BuildConfig.APPLICATION_ID + ":stop"
            )
            try {
                wl.acquire()
                EntityLog.log(this@ServiceSynchronize, "Main stop")
                state!!.running = false
                state!!.semaphore.release()
                join(state!!.thread)
                EntityLog.log(this@ServiceSynchronize, "Main stopped")
                state = null
            } finally {
                wl.release()
                EntityLog.log(this@ServiceSynchronize, "Stop wake lock=" + wl.isHeld)
            }
        }

        fun queue_reload() {
            if (running) {
                lifecycle.submit {
                    stop()
                    start()
                }
            }
        }

        fun queue_start() {
            if (!running) {
                running = true
                lifecycle.submit { start() }
            }
        }

        fun queue_stop() {
            if (running) {
                running = false
                lifecycle.submit {
                    stop()
                    stopSelf()
                }
            }
        }

        private val outboxReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.v(Helper.TAG, outbox!!.name + " run operations")
                executor.submit {
                    val powerManager = getPowerManager(context)
                    val wl = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        BuildConfig.APPLICATION_ID + ":outbox"
                    )
                    try {
                        wl.acquire()
                        val db = DB.getInstance(context)
                        try {
                            Log.i(Helper.TAG, outbox!!.name + " start operations")
                            db.folder().setFolderState(outbox!!.id, "syncing")
                            processOperations(outbox, null, null, null)
                            db.folder().setFolderError(outbox!!.id, null)
                        } catch (ex: Throwable) {
                            Log.e(
                                Helper.TAG, """${outbox!!.name} $ex
${Log.getStackTraceString(ex)}"""
                            )
                            reportError(null, outbox!!.name, ex)
                            db.folder().setFolderError(outbox!!.id, Helper.formatThrowable(ex))
                        } finally {
                            Log.i(Helper.TAG, outbox!!.name + " end operations")
                            db.folder().setFolderState(outbox!!.id, null)
                        }
                    } finally {
                        wl.release()
                        EntityLog.log(this@ServiceSynchronize, "Outbox wake lock=" + wl.isHeld)
                    }
                }
            }
        }
    }

    private fun join(thread: Thread?) {
        var joined = false
        while (!joined) {
            try {
                Log.i(Helper.TAG, "Joining " + thread!!.name)
                thread.join()
                joined = true
                Log.i(Helper.TAG, "Joined " + thread.name)
            } catch (ex: InterruptedException) {
                Log.w(Helper.TAG, thread!!.name + " join " + ex.toString())
            }
        }
    }

    private fun yieldWakelock() {
        try {
            // Give interrupted thread some time to acquire wake lock
            Thread.sleep(500L)
        } catch (ignored: InterruptedException) {
        }
    }

    private inner class ServiceState {
        var running = true
        var thread: Thread? = null
        var semaphore = Semaphore(0)
    }

    companion object {
        private const val NOTIFICATION_SYNCHRONIZE = 1
        private const val CONNECT_BACKOFF_START = 8 // seconds
        private const val CONNECT_BACKOFF_MAX = 1024 // seconds (1024 sec ~ 17 min)
        private const val SYNC_BATCH_SIZE = 20
        private const val DOWNLOAD_BATCH_SIZE = 20
        private const val MESSAGE_AUTO_DOWNLOAD_SIZE = 32 * 1024 // bytes
        private const val ATTACHMENT_AUTO_DOWNLOAD_SIZE = 32 * 1024 // bytes
        private const val RECONNECT_BACKOFF = 90 * 1000L // milliseconds
        const val PI_CLEAR = 1
        const val PI_SEEN = 2
        const val PI_TRASH = 3
        const val PI_IGNORED = 4
        const val ACTION_SYNCHRONIZE_FOLDER = BuildConfig.APPLICATION_ID + ".SYNCHRONIZE_FOLDER"
        const val ACTION_PROCESS_OPERATIONS = BuildConfig.APPLICATION_ID + ".PROCESS_OPERATIONS"

        @JvmStatic
        @Throws(MessagingException::class, IOException::class)
        fun synchronizeMessage(
            context: Context?, folder: EntityFolder?, ifolder: IMAPFolder?,
            imessage: IMAPMessage, found: Boolean
        ): Long {
            val uid = ifolder?.getUID(imessage)
            if (imessage.isExpunged) {
                Log.i(Helper.TAG, folder!!.name + " expunged uid=" + uid)
                throw MessageRemovedException()
            }
            if (imessage.isSet(Flags.Flag.DELETED)) {
                Log.i(Helper.TAG, folder!!.name + " deleted uid=" + uid)
                throw MessageRemovedException()
            }
            val helper = MessageHelper(imessage)
            val seen = helper.seen
            val flagged = helper.flagged
            val db = DB.getInstance(context)

            if (folder == null) {
                Log.w(Helper.TAG, "Invalid folder, folder was null")
                return throw MessagingException("Invalid Folder, folder is null")
            }

            if (uid == null) {
                Log.w(Helper.TAG, "Invalid message UID, it was null")
                return throw MessagingException("Invalid Message UID, uid is null")
            }
            // Find message by uid (fast, no headers required)
            var message = db.message().getMessageByUid(folder.id, uid, found)


            // Find message by Message-ID (slow, headers required)
            // - messages in inbox have same id as message sent to self
            // - messages in archive have same id as original
            if (message == null) {
                // Will fetch headers within database transaction
                val msgid = helper.messageID
                val refs = helper.references
                val reference =
                    if (refs.size == 1 && refs[0].indexOf(BuildConfig.APPLICATION_ID) > 0) refs[0] else msgid
                Log.i(Helper.TAG, "Searching for $msgid / $reference")
                for (dup in db.message().getMessageByMsgId(
                    folder.account, msgid, reference,
                    found
                )) {
                    val dfolder = db.folder().getFolder(dup.folder)
                    val outbox = EntityFolder.OUTBOX == dfolder.type
                    Log.i(
                        Helper.TAG,
                        folder.name + " found as id=" + dup.id + "/" + dup.uid + " folder=" + dfolder.type + ":"
                                + dup.folder + "/" + folder.type + ":" + folder.id + " msgid=" + dup.msgid
                                + " thread=" + dup.thread
                    )
                    if (dup.folder == folder.id || outbox) {
                        val thread = helper.getThreadId(uid)
                        Log.i(
                            Helper.TAG,
                            folder.name + " found as id=" + dup.id + "/" + uid + " msgid=" + msgid
                                    + " thread=" + thread
                        )
                        dup.folder = folder.id
                        dup.uid = uid
                        dup.msgid = msgid
                        dup.thread = thread
                        dup.error = null
                        db.message().updateMessage(dup)
                        message = dup
                    }
                }
            }
            if (message == null) {
                message = EntityMessage()
                message.account = folder.account
                message.folder = folder.id
                message.uid = uid

                if (EntityFolder.ARCHIVE != folder.type) {
                    message.msgid = helper.messageID
                    if (TextUtils.isEmpty(message.msgid)) {
                        Log.w(Helper.TAG, "No Message-ID id=" + message.id + " uid=" + message.uid)
                    }
                }

                message.references = TextUtils.join(" ", helper.references)
                message.inreplyto = helper.inReplyTo
                message.deliveredto = helper.deliveredTo
                message.thread = helper.getThreadId(uid)
                message.from = helper.from
                message.to = helper.to
                message.cc = helper.cc
                message.bcc = helper.bcc
                message.reply = helper.reply
                message.subject = imessage.subject
                message.size = helper.size
                message.content = false
                message.received = imessage.receivedDate.time
                message.sent = if (imessage.sentDate == null) null else imessage.sentDate.time
                message.seen = seen
                message.ui_seen = seen
                message.flagged = false
                message.ui_flagged = false
                message.ui_hide = false
                message.ui_found = found
                message.ui_ignored = false

                if (hasContactsPermission(context)) {
                    try {
                        if (message.from != null) {
                            for (i in message.from.indices) {
                                val email = (message.from[i] as InternetAddress).address
                                var cursor: Cursor? = null
                                try {
                                    val resolver = context?.contentResolver
                                    cursor = resolver?.query(
                                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                                        arrayOf(
                                            ContactsContract.CommonDataKinds.Photo.CONTACT_ID,
                                            ContactsContract.Contacts.DISPLAY_NAME
                                        ),
                                        ContactsContract.CommonDataKinds.Email.ADDRESS + " = ?",
                                        arrayOf(email),
                                        null
                                    )
                                    if (cursor != null && cursor.moveToNext()) {
                                        val colContactId =
                                            cursor.getColumnIndex(ContactsContract.CommonDataKinds.Photo.CONTACT_ID)
                                        val colDisplayName =
                                            cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                                        val contactId = cursor.getLong(colContactId)
                                        val displayName = cursor.getString(colDisplayName)
                                        val uri = ContentUris.withAppendedId(
                                            ContactsContract.Contacts.CONTENT_URI,
                                            contactId
                                        )
                                        message.avatar = uri.toString()
                                        if (!TextUtils.isEmpty(displayName)) {
                                            (message.from[i] as InternetAddress).personal =
                                                displayName
                                        }
                                    }
                                } finally {
                                    cursor?.close()
                                }
                            }
                        }
                    } catch (ex: Throwable) {
                        Log.e(
                            Helper.TAG, """
     $ex
     ${Log.getStackTraceString(ex)}
     """.trimIndent()
                        )
                    }
                }
                message.id = db.message().insertMessage(message)
                Log.i(Helper.TAG, folder.name + " added id=" + message.id + " uid=" + message.uid)
                var sequence = 1
                for (attachment in helper.attachments) {
                    Log.i(
                        Helper.TAG,
                        folder.name + " attachment seq=" + sequence + " name=" + attachment.name
                                + " type=" + attachment.type + " cid=" + attachment.cid
                    )
                    if (!TextUtils.isEmpty(attachment.cid)
                        && db.attachment().getAttachment(message.id, attachment.cid) != null
                    ) {
                        Log.i(Helper.TAG, "Skipping duplicated CID")
                        continue
                    }
                    attachment.message = message.id
                    attachment.sequence = sequence++
                    attachment.id = db.attachment().insertAttachment(attachment)
                }
            } else {
                if (message.seen != seen || message.seen !== message.ui_seen) {
                    message.seen = seen
                    message.ui_seen = seen
                    db.message().updateMessage(message)
                    Log.i(
                        Helper.TAG,
                        folder.name + " updated id=" + message.id + " uid=" + message.uid + " seen=" + seen
                    )
                }
                if (message.flagged != flagged || message.flagged !== message.ui_flagged) {
                    message.flagged = flagged
                    message.ui_flagged = flagged
                    db.message().updateMessage(message)
                    Log.i(
                        Helper.TAG,
                        folder.name + " updated id=" + message.id + " uid=" + message.uid
                                + " flagged=" + flagged
                    )
                }
                if (message.ui_hide) {
                    message.ui_hide = false
                    db.message().updateMessage(message)
                    Log.i(
                        Helper.TAG,
                        folder.name + " unhidden id=" + message.id + " uid=" + message.uid
                    )
                }
            }
            return message.id
        }

        private fun hasContactsPermission(context: Context?): Boolean {
            if (context == null) {
                return false
            }
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }

        @Throws(MessagingException::class, IOException::class)
        private fun downloadMessage(
            context: Context,
            folder: EntityFolder?,
            ifolder: IMAPFolder?,
            imessage: IMAPMessage,
            id: Long
        ) {
            val db = DB.getInstance(context)
            val message = db.message().getMessage(id) ?: return
            val attachments = db.attachment().getAttachments(message.id)
            val helper = MessageHelper(imessage)
            val connectivityManager = getConnectivityManager(context)
            val metered = connectivityManager == null || connectivityManager.isActiveNetworkMetered
            var fetch = false
            if (!message.content) {
                if (!metered || message.size != null && message.size < MESSAGE_AUTO_DOWNLOAD_SIZE) {
                    fetch = true
                }
            }
            if (!fetch) {
                for (attachment in attachments) {
                    if (!attachment.available) {
                        if (!metered || attachment.size != null && attachment.size < ATTACHMENT_AUTO_DOWNLOAD_SIZE) {
                            fetch = true
                            break
                        }
                    }
                }
            }
            if (fetch) {
                Log.i(Helper.TAG, folder!!.name + " fetching message id=" + message.id)
                val fp = FetchProfile()
                fp.add(FetchProfile.Item.ENVELOPE)
                fp.add(FetchProfile.Item.FLAGS)
                fp.add(FetchProfile.Item.CONTENT_INFO) // body structure
                fp.add(UIDFolder.FetchProfileItem.UID)
                fp.add(IMAPFolder.FetchProfileItem.HEADERS)
                fp.add(IMAPFolder.FetchProfileItem.MESSAGE)
                fp.add(FetchProfile.Item.SIZE)
                fp.add(IMAPFolder.FetchProfileItem.INTERNALDATE)
                ifolder!!.fetch(arrayOf<Message>(imessage), fp)
            }
            if (!message.content) {
                if (!metered || message.size != null && message.size < MESSAGE_AUTO_DOWNLOAD_SIZE) {
                    message.write(context, helper.html)
                    db.message().setMessageContent(message.id, true)
                    Log.i(
                        Helper.TAG,
                        folder!!.name + " downloaded message id=" + message.id + " size=" + message.size
                    )
                }
            }
            var iattachments: List<EntityAttachment>? = null
            for (i in attachments.indices) {
                val attachment = attachments[i]
                if (!attachment.available) {
                    if (!metered || attachment.size != null && attachment.size < ATTACHMENT_AUTO_DOWNLOAD_SIZE) {
                        if (iattachments == null) {
                            iattachments = helper.attachments
                        }
                        attachment.part = iattachments!![i].part
                        attachment.download(context, db)
                        Log.i(
                            Helper.TAG,
                            folder!!.name + " downloaded message id=" + message.id + " attachment="
                                    + attachment.name + " size=" + message.size
                        )
                    }
                }
            }
        }

        @JvmStatic
        fun init(context: Context?) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean("enabled", true)) {
                start(context)
            }
        }

        @JvmStatic
        fun start(context: Context?) {
            ContextCompat.startForegroundService(
                context!!,
                Intent(context, ServiceSynchronize::class.java).setAction("start")
            )
        }

        @JvmStatic
        fun stop(context: Context?) {
            ContextCompat.startForegroundService(
                context!!,
                Intent(context, ServiceSynchronize::class.java).setAction("stop")
            )
        }

        @JvmStatic
        fun reload(context: Context?, reason: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            if (prefs.getBoolean("enabled", true)) {
                Log.i(Helper.TAG, "Reload because of '$reason'")
                ContextCompat.startForegroundService(
                    context!!,
                    Intent(context, ServiceSynchronize::class.java).setAction("reload")
                )
            }
        }
    }
}
