package com.pelakauto.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
internal fun DraftPhotoEditor(
    photos: List<DraftPhoto>, errors: Map<String, String>, jwt: String, enabled: Boolean,
    onChange: (List<DraftPhoto>) -> Unit
) {
    val context = LocalContext.current
    var replacing by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<DraftPhoto?>(null) }
    var notice by remember { mutableStateOf("") }
    fun retain(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    val add = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { selected ->
        val unique = selected.distinct().filterNot { uri -> photos.any { it.uri == uri } }
        val room = (8 - photos.size).coerceAtLeast(0)
        val accepted = unique.take(room)
        accepted.forEach(::retain)
        onChange(photos + accepted.map { DraftPhoto(uri = it) })
        notice = if (unique.size > room) "حداکثر ۸ عکس؛ ${unique.size - room} عکس اضافه نشد. برای تغییر انتخاب، حذف یا جایگزینی را بزن." else ""
    }
    val replace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = replacing
        replacing = null
        if (uri != null && id != null) {
            retain(uri)
            onChange(photos.map { if (it.id == id) DraftPhoto(uri = uri) else it })
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("عکس‌ها: ${photos.size} از 8")
        OutlinedButton(enabled = enabled && photos.size < 8, onClick = { add.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text("افزودن عکس")
        }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        photos.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { photo -> key(photo.id) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.height(125.dp).fillMaxWidth().clickable { preview = photo }) {
                        if (photo.uri != null) AsyncImage(photo.uri, "پیش‌نمایش عکس",
                            Modifier.fillMaxSize().graphicsLayer { rotationZ = photo.rotation.toFloat() },
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        else CarPhoto(photo.path, "عکس ذخیره‌شده", jwt, Modifier.fillMaxSize())
                    }
                    Text(errors[photo.id] ?: if (photo.path == null) "آمادهٔ بارگذاری" else "بارگذاری‌شده",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = enabled, onClick = { onChange(photos.filterNot { it.id == photo.id }) }) { Text("× حذف") }
                        TextButton(enabled = enabled, onClick = {
                            replacing = photo.id
                            replace.launch(arrayOf("image/*"))
                        }) { Text("جایگزینی") }
                    }
                    if (photo.uri != null && photo.path == null) TextButton(enabled = enabled, onClick = {
                        onChange(photos.map { if (it.id == photo.id) it.copy(rotation = (it.rotation + 90) % 360) else it })
                    }) { Text("چرخش ۹۰ درجه") }
                }
                } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (preview != null) Dialog(onDismissRequest = { preview = null }) {
            val photo = preview!!
            Surface {
                Column(Modifier.padding(12.dp)) {
                    if (photo.uri != null) AsyncImage(photo.uri, "پیش‌نمایش",
                        Modifier.fillMaxWidth().height(380.dp).graphicsLayer { rotationZ = photo.rotation.toFloat() },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    else CarPhoto(photo.path, "پیش‌نمایش", jwt, Modifier.fillMaxWidth().height(380.dp),
                        androidx.compose.ui.layout.ContentScale.Fit)
                    TextButton(onClick = { preview = null }) { Text("بستن") }
                }
            }
        }
        Text("تغییرات عکس‌ها با ذخیرهٔ پیش‌نویس ثبت می‌شوند.", style = MaterialTheme.typography.bodySmall)
    }
}
