package com.studymate.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import java.io.File

/** Мост между страницей (window.AndroidBridge) и Android. Методы вызываются из JavaScript. */
class AndroidBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(activity).areNotificationsEnabled()
    }

    @JavascriptInterface
    fun showNotification(title: String, body: String) {
        if (!canNotify()) return
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Дедлайны", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            activity, 0,
            Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (e: SecurityException) {
            // разрешение отозвано — просто молчим
        }
    }

    /** Цвета системных панелей под тему приложения. */
    @JavascriptInterface
    fun setBarColors(bg: String, surface: String, light: Boolean) {
        activity.runOnUiThread {
            try {
                val w = activity.window
                w.statusBarColor = parseColor(bg)
                w.navigationBarColor = parseColor(surface)
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.isAppearanceLightStatusBars = light
                c.isAppearanceLightNavigationBars = light
            } catch (e: Exception) {
            }
        }
    }

    /** Сохраняет файл из data:-ссылки (например PDF) в «Загрузки» и открывает его. */
    @JavascriptInterface
    fun saveFile(dataUrl: String, fileName: String) {
        Thread {
            try {
                val comma = dataUrl.indexOf(',')
                if (!dataUrl.startsWith("data:") || comma < 0) throw IllegalArgumentException("not a data url")
                val header = dataUrl.substring(5, comma)
                val payload = dataUrl.substring(comma + 1)
                val bytes = if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
                else Uri.decode(payload).toByteArray(Charsets.ISO_8859_1)
                val mime = header.substringBefore(';').ifBlank { "application/octet-stream" }
                val name = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
                val uri = write(bytes, name, mime)
                activity.runOnUiThread {
                    toast("Сохранено: $name")
                    try {
                        activity.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Открыть файл"
                            )
                        )
                    } catch (e: ActivityNotFoundException) {
                        // нет приложения для просмотра — файл всё равно сохранён
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { toast("Не удалось сохранить файл") }
            }
        }.start()
    }

    @JavascriptInterface
    fun checkForUpdates() {
        activity.runOnUiThread { activity.updater.check(manual = true) }
    }

    @JavascriptInterface
    fun getAppVersion(): String = BuildConfig.VERSION_NAME

    // ---------- вспомогательное ----------

    private fun write(bytes: ByteArray, name: String, mime: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return uri
        }
        // Android 8–9: пишем во внутренний кэш и отдаём через FileProvider
        val dir = File(activity.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }

    private fun parseColor(css: String): Int {
        val s = css.trim()
        if (s.startsWith("rgb")) {
            val n = Regex("[0-9.]+").findAll(s).map { it.value.toFloat().toInt() }.toList()
            if (n.size >= 3) return Color.rgb(n[0], n[1], n[2])
        }
        return Color.parseColor(s)
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val CHANNEL_ID = "deadlines"
    }
}
