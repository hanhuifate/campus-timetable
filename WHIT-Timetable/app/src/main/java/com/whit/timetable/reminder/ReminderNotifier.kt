package com.whit.timetable.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.RemoteViews
import com.whit.timetable.R
import com.whit.timetable.data.AppPrefs

/**
 * 上课提醒的通知与提示音。
 * 通知渠道只用来显示，提示音和震动都由 App 自己发：渠道声音建好后不可修改，
 * 而且部分机型（如小米/澎湃）会把渠道声音压掉，只剩震动。
 * 响铃期间通知上带「停止」按钮，按音量键也会停。
 */
object ReminderNotifier {

    const val TONE_VIBRATE = "vibrate"
    const val TONE_NOTIF = "notif"
    const val TONE_ALARM = "alarm"
    const val TONE_RINGTONE = "ringtone"

    /** 设置页展示顺序。 */
    val TONES = listOf(TONE_VIBRATE, TONE_NOTIF, TONE_ALARM, TONE_RINGTONE)

    /** 设置页的权限引导要指到这个渠道。 */
    const val CHANNEL_ALERT = "reminder_alert_v4"
    private const val CHANNEL_ALERT_NAME = "上课提醒"

    /** 早期按提示音拆开的渠道已弃用，留着会让系统设置里出现一堆重复项。 */
    private val LEGACY_CHANNELS = listOf(
        "reminder_alert",
        "reminder_alert_v2",
        "reminder_alert_v3",
        "reminder_vibrate",
        "reminder_sound",
        "reminder_alarm",
        "reminder_ringtone",
        "reminder_preview"
    )

    /** 通知音本身很短，给个兜底上限。 */
    private const val NOTIF_MAX_MS = 10_000L

    private const val STOP_REQUEST_CODE = 20261025

    private val handler = Handler(Looper.getMainLooper())

    /** 闹钟音 / 铃声按设置好的时长响，响铃期间通知常驻并带「停止」按钮。 */
    fun popsUp(tone: String): Boolean = tone == TONE_ALARM || tone == TONE_RINGTONE

    /** 持有当前正在响的铃声，避免播放对象被回收后声音中途断掉。 */
    private var playing: Ringtone? = null
    /** 震动由 App 自己发：系统通知分类里的「振动」开关默认可能是关的，靠渠道不可靠。 */
    private var vibrator: Vibrator? = null
    private var volumeObserver: ContentObserver? = null
    private var observerContext: Context? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var screenContext: Context? = null

    /** 最近一条提醒的通知 id，停止时把它一并撤掉。 */
    private var lastId = 0

    fun label(tone: String): String = when (tone) {
        TONE_NOTIF -> "通知音"
        TONE_ALARM -> "闹钟音"
        TONE_RINGTONE -> "铃声"
        else -> "仅震动"
    }

    fun notify(context: Context, title: String, body: String, tone: String, startAt: Long = 0L) {
        playTone(context, tone)
        post(context, reminderId(startAt), title, body, tone)
    }

    /** 设置页的试听：震动、提示音都和正式提醒走同一套逻辑。 */
    fun preview(context: Context, minutes: Int, tone: String) {
        playTone(context, tone)
        post(
            context,
            previewId(),
            "快要上课：高等数学A(1)",
            "示例：第1-2节 08:30-10:05 · 6号楼 6202，${minutes} 分钟后上课",
            tone
        )
    }

    /** 停掉正在响的提示音并撤掉通知（通知上的「停止」按钮、按音量键、点通知进 App 都会走这里）。 */
    fun stop(context: Context) {
        stopSound()
        if (lastId != 0) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(lastId)
            lastId = 0
        }
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        playing?.stop()
        playing = null
        vibrator?.cancel()
        vibrator = null
        stopWatchingVolume()
        stopWatchingScreen()
    }

    /** 先震动，不依赖系统通知分类里的振动开关。 */
    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return
        this.vibrator = vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 320, 180, 320), -1))
    }

    private fun playTone(context: Context, tone: String) {
        if (tone == TONE_VIBRATE) {
            vibrate(context)
            return
        }
        val uri = toneUri(tone) ?: return
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setUsage(
                if (tone == TONE_ALARM) AudioAttributes.USAGE_ALARM
                else AudioAttributes.USAGE_NOTIFICATION
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        stopSound()
        // 震动要放在 stopSound 之后，否则会被一起取消
        vibrate(context)
        ringtone.play()
        playing = ringtone
        watchVolume(context)
        watchScreen(context)
        // 闹钟音 / 铃声响到设置好的时长；通知音本身很短，给个兜底上限
        val limit = if (popsUp(tone)) {
            AppPrefs(context).ringSeconds * 1000L
        } else {
            NOTIF_MAX_MS
        }
        val app = context.applicationContext
        handler.postDelayed(
            {
                // 有全屏卡片的两种：响完连卡片和通知一起收掉
                if (popsUp(tone)) stop(app) else stopSound()
            },
            limit
        )
    }

    /**
     * 按音量键（任一音量变化）时停止响铃。没有 Activity 时拿不到按键事件，
     * 只能监听音量设置的变化来近似实现。新版系统按设备拆成了 volume_ring_speaker 这类键，
     * 所以直接监听整个 Settings.System 表，筛出 volume_ 开头的项。
     */
    private fun watchVolume(context: Context) {
        val app = context.applicationContext
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val path = uri?.path.orEmpty()
                if (path.isNotEmpty() && !path.contains("volume")) return
                stop(app)
            }
        }
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
        observerContext = app
    }

    private fun stopWatchingVolume() {
        val observer = volumeObserver ?: return
        observerContext?.contentResolver?.unregisterContentObserver(observer)
        volumeObserver = null
        observerContext = null
    }

    /** 按电源键息屏 / 锁屏时停止响铃。 */
    private fun watchScreen(context: Context) {
        val app = context.applicationContext
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stop(app)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
        screenContext = app
    }

    private fun stopWatchingScreen() {
        val receiver = screenReceiver ?: return
        runCatching { screenContext?.unregisterReceiver(receiver) }
        screenReceiver = null
        screenContext = null
    }

    /**
     * 每次都用新的通知 id：同一个 id 再次 notify 会被系统当成「更新」，
     * 既不响铃也不震动（旧通知还挂在通知栏时尤其明显）。
     */
    private fun reminderId(startAt: Long): Int =
        if (startAt > 0L) 1_000_000 + (startAt / 60_000L % 500_000L).toInt()
        else 1_000_000 + (System.currentTimeMillis() / 60_000L % 500_000L).toInt()

    private fun previewId(): Int =
        1_500_000 + (System.currentTimeMillis() / 1000L % 500_000L).toInt()

    private fun post(context: Context, id: Int, title: String, body: String, tone: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val content = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            context,
            STOP_REQUEST_CODE,
            Intent(context, ReminderStopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (popsUp(tone)) {
            // 自定义 RemoteViews 会被 HyperOS 收进「更多通知」不弹横幅，这里保持系统默认视图 + 停止按钮
            builder.setOngoing(false)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(context, android.R.drawable.ic_delete),
                        "停止",
                        stop
                    ).build()
                )
        }

        lastId = id
        manager.notify(id, builder.build())
    }

    private fun alertView(
        context: Context,
        title: String,
        body: String,
        stop: PendingIntent
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notif_reminder).apply {
        setTextViewText(R.id.notif_title, title)
        setTextViewText(R.id.notif_body, body)
        setOnClickPendingIntent(R.id.notif_stop, stop)
    }

    private fun toneUri(tone: String): Uri? = when (tone) {
        TONE_NOTIF -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        TONE_ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        TONE_RINGTONE -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        else -> null
    }

    /**
     * 渠道必须有声音和震动，否则系统会把通知算成静默通知，收进「更多通知」而不弹横幅；
     * 真正的提示音由 App 自己播放，这里给个系统默认铃声占位。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                CHANNEL_ALERT_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 320, 180, 320)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                description = "上课前提醒，提示音由 App 播放"
            }
        )
        LEGACY_CHANNELS.forEach { manager.deleteNotificationChannel(it) }
    }
}
