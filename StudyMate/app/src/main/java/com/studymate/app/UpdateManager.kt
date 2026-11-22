package com.studymate.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Проверка и установка обновлений через GitHub Releases.
 *
 * В каждом релизе лежат два файла: StudyMate.apk и update.json.
 * Адрес вида https://github.com/<владелец>/<репозиторий>/releases/latest/download/update.json
 * всегда отдаёт файл из самого свежего релиза, так что собственный сервер не нужен.
 */
class UpdateManager(private val activity: Activity) {

    private class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val sha256: String
    )

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val baseUrl = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/"
    private val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)

    private var pendingApk: File? = null
    @Volatile private var cancelled = false
    private var progressDialog: AlertDialog? = null

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Вызывается при запуске: проверяет не чаще раза в 6 часов и молчит, если обновления нет. */
    fun checkOnStartup() {
        val last = prefs.getLong("lastCheck", 0L)
        if (System.currentTimeMillis() - last < SIX_HOURS) return
        check(manual = false)
    }

    /** manual = true — пользователь нажал кнопку: показываем результат в любом случае. */
    fun check(manual: Boolean) {
        if (BuildConfig.UPDATE_REPO.startsWith("OWNER")) {
            if (manual) toast("Адрес обновлений не настроен (собирайте приложение через GitHub Actions)")
            return
        }
        cleanOldFiles()
        io.execute {
            try {
                val json = JSONObject(httpGetText(baseUrl + "update.json"))
                val info = Info(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", "?"),
                    notes = json.optString("notes", ""),
                    sha256 = json.optString("sha256", "")
                )
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                ui.post {
                    if (activity.isFinishing) return@post
                    if (info.versionCode > BuildConfig.VERSION_CODE) {
                        showUpdateDialog(info)
                    } else if (manual) {
                        toast("У вас последняя версия (${BuildConfig.VERSION_NAME})")
                    }
                }
            } catch (e: Exception) {
                if (manual) ui.post { toast("Не удалось проверить обновления. Проверьте интернет.") }
            }
        }
    }

    /** Вызывать из Activity.onResume: если человек выдал разрешение на установку — продолжаем. */
    fun onResume() {
        val apk = pendingApk ?: return
        if (canInstall()) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    private fun showUpdateDialog(info: Info) {
        val text = buildString {
            append("Версия ${info.versionName} (у вас ${BuildConfig.VERSION_NAME})")
            if (info.notes.isNotBlank()) append("\n\n").append(info.notes.trim())
        }
        AlertDialog.Builder(activity)
            .setTitle("Доступно обновление")
            .setMessage(text)
            .setPositiveButton("Обновить") { _, _ -> download(info) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun download(info: Info) {
        cancelled = false
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(activity).apply { text = "Скачивание…" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(label)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        progressDialog = AlertDialog.Builder(activity)
            .setTitle("Обновление ${info.versionName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Отмена") { _, _ -> cancelled = true }
            .show()

        io.execute {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "StudyMate-${info.versionName}.apk")
            try {
                downloadFile(baseUrl + "StudyMate.apk", target) { percent ->
                    ui.post {
                        if (percent >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = percent
                            label.text = "Скачивание… $percent%"
                        }
                    }
                }
                if (cancelled) {
                    target.delete()
                    ui.post { progressDialog?.dismiss() }
                    return@execute
                }
                if (info.sha256.isNotBlank() && !sha256(target).equals(info.sha256, ignoreCase = true)) {
                    target.delete()
                    ui.post {
                        progressDialog?.dismiss()
                        toast("Файл обновления повреждён. Попробуйте ещё раз.")
                    }
                    return@execute
                }
                ui.post {
                    progressDialog?.dismiss()
                    install(target)
                }
            } catch (e: Exception) {
                target.delete()
                ui.post {
                    progressDialog?.dismiss()
                    if (!cancelled) toast("Не удалось скачать обновление. Проверьте интернет.")
                }
            }
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(apk: File) {
        if (!canInstall()) {
            pendingApk = apk
            AlertDialog.Builder(activity)
                .setTitle("Нужно разрешение")
                .setMessage("Чтобы установить обновление, разрешите StudyMate устанавливать приложения. Включите переключатель на следующем экране и вернитесь назад.")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    activity.startActivity(i)
                }
                .setNegativeButton("Отмена") { _, _ -> pendingApk = null }
                .show()
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            toast("Не удалось открыть установщик")
        }
    }

    private fun cleanOldFiles() {
        io.execute {
            File(activity.cacheDir, "updates").listFiles()?.forEach { f ->
                // свежескачанный файл, ожидающий установки, не трогаем
                if (f != pendingApk && System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L) f.delete()
            }
        }
    }

    // ---------- сеть ----------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "StudyMate-Updater")
        return c
    }

    private fun httpGetText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var lastPercent = -2
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != lastPercent) {
                            lastPercent = p
                            onProgress(p)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val SIX_HOURS = 6 * 60 * 60 * 1000L
    }
}
