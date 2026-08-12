package com.bplog.holter

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
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
        const val CHANNEL_ID = "bplog_holter"
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

            // Calculate next time
            val nextTime = fmt.format(Date(now + intervalSeconds * 1000L))
            delay(2000) // Brief pause to show result
            updateNotification("Last: ${fmt.format(Date(now))} · Next at $nextTime")

            // Countdown delay with audio beeps before next measurement
            runCountdownDelay(intervalSeconds)
        }
    }

    private suspend fun runCountdownDelay(intervalSeconds: Int) {
        val totalMs = (intervalSeconds * 1000L) - 2000L
        val tenMinMs = 10 * 60 * 1000L   // 10 minutes
        val sixMinMs = 6 * 60 * 1000L    // 6 minutes
        val fiveMinMs = 5 * 60 * 1000L   // 5 minutes

        if (totalMs > tenMinMs) {
            // Delay until T-10 minutes
            delay(totalMs - tenMinMs)
            playBeeps(3) // 3 beeps at 10 minutes before

            // Delay 4 minutes until T-6 minutes
            delay(tenMinMs - sixMinMs)
            playBeeps(2) // 2 beeps at 6 minutes before

            // Delay 1 minute until T-5 minutes
            delay(sixMinMs - fiveMinMs)
            playBeeps(1) // 1 beep at 5 minutes before

            // Delay final 5 minutes until next measurement
            delay(fiveMinMs)
        } else {
            // For intervals shorter than 10 minutes
            if (totalMs > 0) delay(totalMs)
        }
    }

    private suspend fun playBeeps(times: Int) = withContext(Dispatchers.Default) {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            repeat(times) { count ->
                toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                if (count < times - 1) delay(300)
            }
            delay(200)
            toneGen.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play beeps", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "BPLog", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Blood pressure holter monitoring" }
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
