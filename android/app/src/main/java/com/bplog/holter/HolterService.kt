package com.bplog.holter

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bplog.holter.db.AppDatabase
import com.bplog.holter.db.Measurement
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class HolterService : Service() {

    companion object {
        const val TAG = "HolterService"
        const val CHANNEL_ID = "bplog_holter_alerts_v2"
        const val NOTIFICATION_ID = 1
        const val EXTRA_INTERVAL = "interval_seconds"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bleManager: BleManager
    private lateinit var dao: com.bplog.holter.db.MeasurementDao
    private var running = false

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this)
        dao = AppDatabase.get(this).measurementDao()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val interval = intent?.getIntExtra(EXTRA_INTERVAL, 300) ?: 300
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        if (!running) {
            running = true
            scope.launch { measurementLoop(interval) }
        }
        return START_STICKY
    }

    private suspend fun measurementLoop(intervalSeconds: Int) {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (running) {
            updateNotification("Measuring...")
            val reading = bleManager.takeMeasurement()
            val now = System.currentTimeMillis()

            if (reading != null) {
                dao.insert(Measurement(
                    timestamp = now,
                    systolic = reading.systolic,
                    diastolic = reading.diastolic,
                    map = reading.map,
                    hr = reading.hr,
                    status = reading.status
                ))
                val timeStr = fmt.format(Date(now))
                val text = if (reading.status == 2)
                    "$timeStr · ${reading.systolic}/${reading.diastolic} BPM ${reading.hr}"
                else
                    "$timeStr · Error (status ${reading.status})"
                updateNotification("Last: $text")
                Log.i(TAG, text)
            } else {
                updateNotification("Last: ${fmt.format(Date(now))} · Failed (no device?)")
                Log.w(TAG, "Measurement failed")
            }

            val nextTime = fmt.format(Date(now + intervalSeconds * 1000L))
            delay(2000) // Brief pause to display result
            updateNotification("Last: ${fmt.format(Date(now))} · Next at $nextTime")

            // Run countdown delay with audio beeps and vibration
            runCountdownDelay(intervalSeconds)
        }
    }

    private suspend fun runCountdownDelay(intervalSeconds: Int) {
        val totalMs = (intervalSeconds * 1000L) - 2000L
        if (totalMs <= 0) return

        val tenMinMs = 10 * 60 * 1000L   // 10 minutes
        val sixMinMs = 6 * 60 * 1000L    // 6 minutes
        val fiveMinMs = 5 * 60 * 1000L   // 5 minutes

        if (totalMs >= tenMinMs) {
            // Long intervals (>= 10 minutes)
            delay(totalMs - tenMinMs)
            playBeepsAndVibrate(3) // 3 beeps at T-10 min

            delay(tenMinMs - sixMinMs)
            playBeepsAndVibrate(2) // 2 beeps at T-6 min

            delay(sixMinMs - fiveMinMs)
            playBeepsAndVibrate(1) // 1 beep at T-5 min

            delay(fiveMinMs)
        } else {
            // Short intervals (< 10 minutes, e.g. for quick testing)
            val step = totalMs / 4
            delay(step)
            playBeepsAndVibrate(3)

            delay(step)
            playBeepsAndVibrate(2)

            delay(step)
            playBeepsAndVibrate(1)

            delay(totalMs - (step * 3))
        }
    }

    private suspend fun playBeepsAndVibrate(times: Int) = withContext(Dispatchers.Default) {
        try {
            // STREAM_ALARM plays audio loudly through the alarm volume channel
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            
            // Trigger device vibration
            triggerVibration()

            repeat(times) { count ->
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                if (count < times - 1) delay(350)
            }
            delay(200)
            toneGen.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio/vibration", e)
        }
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BPLog Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Blood pressure holter alerts"
            enableVibration(true)
            enableLights(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BPLog")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
