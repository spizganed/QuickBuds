package com.spizganed.quickbuds.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.spizganed.quickbuds.R

/**
 * Find phone: the buds ask the phone to ring (`0x0502 05`) or to stop (`0x0502 06`), PROTOCOL.md section 11.
 * The ring is the default ringtone on the alarm stream at one step below full volume, with vibration.
 * A notification carries a Stop action. The ring stops by itself after [LIMIT_MS].
 */
object PhoneRinger {
    const val ACTION_STOP = "com.spizganed.quickbuds.action.FIND_PHONE_STOP"
    private const val CHANNEL_ID = "QuickBuds_FindPhone"
    private const val NOTIFICATION_ID = 7301
    private const val LIMIT_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    /** The alarm volume before the ring, put back on stop. -1: nothing to put back. */
    private var savedVolume = -1
    private var app: Context? = null
    private val timeout = Runnable { app?.let { stopNow(it) } }

    fun ring(context: Context) = main.post { ringNow(context.applicationContext) }

    fun stop(context: Context) = main.post { stopNow(context.applicationContext) }

    private fun ringNow(c: Context) {
        if (player != null) return
        app = c
        val audio = c.getSystemService(AudioManager::class.java)
        runCatching {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, (max - 1).coerceAtLeast(1), 0)
        }.onFailure { savedVolume = -1 }   // Do Not Disturb can refuse the change
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(c, RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator = vibratorOf(c)?.also {
            runCatching { it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0), attrs) }
        }
        notify(c)
        main.postDelayed(timeout, LIMIT_MS)
    }

    private fun stopNow(c: Context) {
        main.removeCallbacks(timeout)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (savedVolume >= 0) runCatching {
            c.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        }
        savedVolume = -1
        c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun vibratorOf(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)

    private fun notify(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, c.getString(R.string.row_find_phone_title), NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null); enableVibration(false) }   // the ring plays the sound itself
        )
        val stop = PendingIntent.getBroadcast(c, 0, Intent(c, StopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentTitle(c.getString(R.string.row_find_phone_title))
            .setContentText(c.getString(R.string.find_phone_ringing))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.find_stop), stop).build())
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    /** The notification's Stop action, a tap on it, or a swipe. */
    class StopReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP) stop(context)
        }
    }
}
