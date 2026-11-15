package com.pelakauto.nativeapp

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class AccountEntry(val title: String, val icon: ImageVector, val action: String)

@Composable
internal fun PelakAccountMenu(
    email: String,
    onNavigate: (Int) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    val entries = listOf(
        AccountEntry("آگهی‌های من", Icons.Outlined.ListAlt, "mine"),
        AccountEntry("نمایشگاه مجازی", Icons.Outlined.Storefront, "dealer"),
        AccountEntry("نشان‌ها", Icons.Outlined.BookmarkBorder, "favorites"),
        AccountEntry("بازدیدهای اخیر", Icons.Outlined.History, "recent"),
        AccountEntry("تنظیمات", Icons.Outlined.Settings, "settings"),
        AccountEntry("امنیت و گزارش تخلف", Icons.Outlined.Security, "safety"),
        AccountEntry("اخبار و اطلاعیه‌های پلاک", Icons.Outlined.Article, "news"),
        AccountEntry("راهنما و پشتیبانی", Icons.Outlined.HelpOutline, "help"),
        AccountEntry("درباره پلاک", Icons.Outlined.Info, "about")
    )
    fun openSite() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pelakauto.com")))
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Text("پلاک من", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            Text(email, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            HorizontalDivider()
        }
        items(entries.size) { index ->
            val entry = entries[index]
            Row(
                Modifier.fillMaxWidth().clickable {
                    when (entry.action) {
                        "mine" -> onNavigate(3)
                        "favorites" -> onNavigate(6)
                        "settings" -> {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName)))
                        }
                        "help", "news", "safety", "dealer" -> dialog = entry.action
                        "about", "recent" -> dialog = entry.action
                    }
                }.padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(entry.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Outlined.ChevronLeft, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        item {
            TextButton(onClick = onLogout, modifier = Modifier.padding(14.dp)) {
                Text("خروج از حساب")
            }
            Text("PELAK Android · نسخه آزمایشی", modifier = Modifier.padding(horizontal = 18.dp))
        }
    }
    if (dialog != null) {
        val key = dialog!!
        val title = when (key) {
            "dealer" -> "نمایشگاه مجازی"
            "favorites" -> "نشان‌ها"
            "recent" -> "بازدیدهای اخیر"
            "safety" -> "امنیت و گزارش تخلف"
            "news" -> "اخبار و اطلاعیه‌ها"
            "help" -> "راهنما و پشتیبانی"
            else -> "درباره پلاک"
        }
        val description = when (key) {
            "favorites", "recent" -> "این بخش هنوز در نسخه آزمایشی اندروید فعال نشده است؛ تا آماده‌شدن ذخیره‌سازی و همگام‌سازی، داده‌ای نمایش داده نمی‌شود."
            "dealer" -> "مدیریت نمایشگاه در نسخه وب پلاک در دسترس است؛ بخش بومی اندروید هنوز تکمیل نشده است."
            "help" -> "برای ارتباط با پشتیبانی، بخش پشتیبانی سایت پلاک را باز کن."
            "safety" -> "برای گزارش تخلف از گزینه «گزارش آگهی» در جزئیات خودرو استفاده کن. راهنمای امنیت در سایت موجود است."
            "news" -> "اطلاعیه‌های رسمی پلاک را در سایت دنبال کن."
            else -> "پلاک؛ خودروی بعدی تو، اینجاست. اپلیکیشن بومی اندروید، مستقل از وب‌ویو."
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                if (key in listOf("dealer", "help", "news", "safety")) {
                    TextButton(onClick = { dialog = null; openSite() }) { Text("بازکردن سایت پلاک") }
                } else TextButton(onClick = { dialog = null }) { Text("متوجه شدم") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("بستن") } }
        )
    }
}
