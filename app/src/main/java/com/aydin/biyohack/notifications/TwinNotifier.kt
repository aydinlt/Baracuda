package com.aydin.biyohack.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aydin.biyohack.twin.TwinOutput
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "twin_protocol"
private const val NOTIFICATION_ID = 1001

/**
 * TwinOutput'u bildirime çevirir. Yalnızca headline/brief gösterilir —
 * clinical_flag'ler ASLA bildirime çıkmaz (bkz. 10-DIGITAL-TWIN-TASARIM.md
 * Bölüm 5: "clinical domainli hiçbir action bildirime çıkmaz. clinical_flags
 * yalnızca içeride gösterilir"). Aksiyonlar da bildirime taşınmaz, sadece
 * kullanıcıyı İkiz ekranını açmaya yönlendirir — detay orada.
 */
@Singleton
class TwinNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "İkiz protokolü",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission") // izin aşağıda manuel kontrol ediliyor
    fun notify(output: TwinOutput) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(output.headline)
            .setContentText(output.brief)
            .setStyle(NotificationCompat.BigTextStyle().bigText(output.brief))
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
