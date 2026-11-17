package com.pelakauto.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

internal data class SupportThread(val id: String, val subject: String, val status: String)
internal data class SupportMessage(val mine: Boolean, val body: String)
internal data class ShowroomProfile(
    val name: String = "", val city: String = "", val phone: String = "",
    val bio: String = "", val status: String = ""
)

@Composable
internal fun PelakSupportPage(api: PelakApi, session: PelakSession) {
    val scope = rememberCoroutineScope()
    var threads by remember { mutableStateOf(emptyList<SupportThread>()) }
    var messages by remember { mutableStateOf(emptyList<SupportMessage>()) }
    var active by remember { mutableStateOf<SupportThread?>(null) }
    var composing by remember { mutableStateOf(false) }
    var pendingThread by remember { mutableStateOf<SupportThread?>(null) }
    var pendingMessageId by remember { mutableStateOf<String?>(null) }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(active?.id, refresh) {
        loading = true
        error = ""
        try {
            val thread = active
            if (thread == null) threads = api.supportThreads(session)
            else messages = api.supportMessages(session, thread)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = "دریافت گفتگوها انجام نشد. دوباره تلاش کن."
        } finally {
            loading = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null || composing) {
                    TextButton(enabled = !busy, onClick = {
                        active = null
                        composing = false
                        pendingThread = null
                        pendingMessageId = null
                        subject = ""
                        body = ""
                        messages = emptyList()
                        refresh++
                    }) { Text("گفتگوهای من") }
                } else {
                    Button(onClick = { composing = true }) { Text("پیام تازه") }
                }
                TextButton(enabled = !loading && !busy, onClick = { refresh++ }) { Text("تازه‌سازی") }
            }
        }
        if (active == null && !composing) {
            items(threads, key = { it.id }) { thread ->
                ListItem(headlineContent = { Text(thread.subject) },
                    supportingContent = { Text(if (thread.status == "closed") "بسته‌شده" else if (thread.status == "answered") "پاسخ داده شده" else "باز") },
                    modifier = Modifier.clickable {
                        messages = emptyList()
                        active = thread
                    })
                HorizontalDivider()
            }
            if (threads.isEmpty() && !loading && error.isEmpty()) item { Text("هنوز گفتگویی نداری.") }
        } else {
            val thread = active
            if (thread != null) {
                item { Text(thread.subject, style = MaterialTheme.typography.titleMedium) }
                items(messages) { message ->
                    ListItem(headlineContent = { Text(if (message.mine) "شما" else "پشتیبانی پلاک") },
                        supportingContent = { Text(message.body) })
                }
            } else item {
                OutlinedTextField(subject, { subject = it.take(120) }, enabled = !busy && pendingThread == null,
                    label = { Text("موضوع") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (thread?.status == "closed") item { Text("این گفتگو بسته شده است.") }
            else item {
                OutlinedTextField(body, { body = it.take(3000) }, enabled = !busy && pendingMessageId == null, label = { Text("پیام") },
                    minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && body.isNotBlank() && (thread != null || subject.trim().length >= 3), onClick = {
                    if (!busy) {
                        busy = true
                        error = ""
                        scope.launch {
                            try {
                                val target = thread ?: pendingThread ?: SupportThread(
                                    UUID.randomUUID().toString(), subject.trim(), "open"
                                ).also { pendingThread = it }
                                if (thread == null) api.createSupportThread(session, target)
                                val messageId = pendingMessageId ?: UUID.randomUUID().toString().also { pendingMessageId = it }
                                api.sendSupportMessage(session, target, messageId, body.trim())
                                body = ""
                                pendingMessageId = null
                                pendingThread = null
                                active = target
                                composing = false
                                refresh++
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                error = "ارسال پیام تأیید نشد. می‌توانی دوباره تلاش کنی."
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("ارسال پیام") }
            }
        }
    }
}

@Composable
internal fun PelakShowroomPage(api: PelakApi, session: PelakSession) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ShowroomProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        try {
            profile = api.myShowroom(session) ?: ShowroomProfile()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            info = "دریافت مشخصات نمایشگاه ناموفق بود."
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (info.isNotEmpty()) Text(info)
        val value = profile
        if (value == null && !loading) TextButton(onClick = { info = ""; reload++ }) { Text("تلاش دوباره") }
        if (value != null) {
            if (value.status == "rejected") Text("پروفایل نمایشگاه نیاز به بررسی پشتیبانی دارد.")
            else {
                Text("با ذخیره، نام و شهر نمایشگاه و اطلاعات اختیاری واردشده در پروفایل عمومی نمایش داده می‌شوند.")
                ShowroomField(value.name, { profile = value.copy(name = it.take(100)) }, "نام نمایشگاه", !saving)
                ShowroomField(value.city, { profile = value.copy(city = it.take(80)) }, "شهر", !saving)
                ShowroomField(value.phone, { profile = value.copy(phone = it.take(22)) }, "شماره تماس عمومی (اختیاری)", !saving)
                OutlinedTextField(value.bio, { profile = value.copy(bio = it.take(700)) }, enabled = !saving,
                    label = { Text("معرفی (اختیاری)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Button(enabled = !saving && value.name.trim().length >= 2 && value.city.trim().length >= 2,
                    onClick = {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    api.saveShowroom(session, value)
                                    info = "مشخصات نمایشگاه ذخیره شد."
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    info = "ذخیرهٔ مشخصات نمایشگاه انجام نشد."
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }) { Text("ذخیرهٔ پروفایل نمایشگاه") }
            }
        }
    }
}

@Composable
private fun ShowroomField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(value, onChange, enabled = enabled, singleLine = true, label = { Text(label) },
        modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun PelakNewsPage() {
    var notes by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        error = false
        try {
            val release = loadPelakRelease().getJSONObject("android")
            notes = listOf(release.optString("versionName"), release.optString("notes"))
                .filter { it.isNotBlank() }.joinToString("\n\n")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            error = true
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("آخرین اطلاعیهٔ نسخهٔ اندروید", style = MaterialTheme.typography.titleMedium)
        when {
            error -> Text("دریافت اطلاعیه انجام نشد.")
            notes == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Text(notes.orEmpty().ifBlank { "فعلاً اطلاعیه‌ای منتشر نشده است." })
        }
        TextButton(onClick = { refresh++ }) { Text("تازه‌سازی") }
    }
}
