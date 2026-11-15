package com.pelakauto.nativeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Only our own fixed HTTPS endpoints are trusted. Metadata cannot redirect downloads to arbitrary hosts.
private const val UPDATE_MANIFEST = "https://pelakauto.com/pelak-release.json"
private const val APK_DOWNLOAD = "https://pelakauto.com/downloads/PELAK-Native-v0.2-preview.apk"
private data class UpdateInfo(val code: Int, val label: String, val notes: String, val required: Boolean)

private suspend fun fetchUpdateInfo(): UpdateInfo = withContext(Dispatchers.IO) {
    val conn = URL(UPDATE_MANIFEST).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val data = JSONObject(body).getJSONObject("android")
        val version = data.getInt("versionCode")
        val minimum = data.optInt("minimumVersionCode", 0)
        UpdateInfo(version, data.optString("versionName", ""), data.optString("notes", ""), minimum > BuildConfig.VERSION_CODE)
    } finally { conn.disconnect() }
}

@Composable
internal fun NativeUpdateChecker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var checked by remember { mutableStateOf(false) }

    fun check(manual: Boolean) {
        if (checking) return
        scope.launch {
            checking = true
            try {
                val latest = fetchUpdateInfo()
                update = if (latest.code > BuildConfig.VERSION_CODE) latest else null
                if (manual && update == null) message = "نسخه پلاک به‌روز است (نسخه ${BuildConfig.VERSION_NAME})."
            } catch (_: Exception) {
                if (manual) message = "بررسی نسخه ممکن نشد. اتصال اینترنت را بررسی کن."
            } finally { checking = false }
        }
    }

    LaunchedEffect(Unit) { if (!checked) { checked = true; check(false) } }

    TextButton(onClick = { check(true) }, enabled = !checking) {
        Text(if (checking) "در حال بررسی…" else "بررسی به‌روزرسانی")
    }

    update?.let { available ->
        AlertDialog(
            onDismissRequest = { if (!available.required) update = null },
            title = { Text("نسخه جدید پلاک آماده است") },
            text = { Text("نسخه ${available.label}\n${available.notes}\nبرای نصب، فایل APK را دریافت کن و نصب را تأیید کن.") },
            confirmButton = {
                Button(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_DOWNLOAD)).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        message = "مرورگر برای دریافت فایل پیدا نشد."
                    }
                }) { Text("دریافت به‌روزرسانی") }
            },
            dismissButton = {
                if (!available.required) TextButton(onClick = { update = null }) { Text("بعداً") }
            }
        )
    }
    message?.let { msg ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("وضعیت به‌روزرسانی") },
            text = { Text(msg) }, confirmButton = { TextButton(onClick = { message = null }) { Text("متوجه شدم") } })
    }
}
