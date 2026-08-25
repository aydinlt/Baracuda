package com.aydin.biyohack.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aydin.biyohack.MainActivity
import com.aydin.biyohack.twin.TwinOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "twin_protocol"
private const val NOTIFICATION_ID = 1001
private const val REMINDER_CHANNEL_ID = "daily_reminder"
private const val REMINDER_NOTIFICATION_ID = 1002

/**
 * TwinOutput'u bildirime çevirir. Yalnızca headline/brief gösterilir —
 * clinical_flag'ler ASLA bildirime çıkmaz (bkz. 10-DIGITAL-TWIN-TASARIM.md
 * Bölüm 5: "clinical domainli hiçbir action bildirime çıkmaz. clinical_flags
 * yalnızca içeride gösterilir"). Aksiyonlar da bildirime taşınmaz, sadece
 * kullanıcıyı İkiz ekranını açmaya yönlendirir — detay orada.
 *
 * ÖNEMLİ: Bu yönlendirme yorumda vardı ama hiç uygulanmamıştı — bildirime
 * `PendingIntent` hiç eklenmemişti, dokunmak yalnızca bildirimi kapatıyordu
 * (`setAutoCancel`). Artık MainActivity'yi `EXTRA_OPEN_TWIN` ile açıyor.
 */
@Singleton
class TwinNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "İkiz protokolü", NotificationManager.IMPORTANCE_DEFAULT)
            )
            // Ayrı kanal: İkiz'in klinik/protokol çıktısından farklı bir amaç taşıyor
            // (bkz. MiddayReminderWorker) — kullanıcı ikisini ayrı ayrı kapatabilsin.
            manager?.createNotificationChannel(
                NotificationChannel(REMINDER_CHANNEL_ID, "Günlük hatırlatmalar", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    @SuppressLint("MissingPermission") // izin aşağıda manuel kontrol ediliyor
    fun notify(output: TwinOutput) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        val openTwinIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TWIN, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openTwinIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(output.headline)
            .setContentText(output.brief)
            .setStyle(NotificationCompat.BigTextStyle().bigText(output.brief))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Klinik/protokol çıktısıyla ilgisi olmayan basit metin bildirimi —
     * MiddayReminderWorker'ın "bugün henüz su/protein logu yok" hatırlatması
     * için. Dokunulduğunda LogScreen'i AÇMAZ (MainActivity'de o yönlendirme
     * henüz yok) — yalnızca uygulamayı öne getirir, bilinçli bir sınırlama.
     */
    @SuppressLint("MissingPermission")
    fun notifyReminder(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REMINDER_NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(REMINDER_NOTIFICATION_ID, notification)
    }
}
