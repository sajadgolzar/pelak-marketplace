package com.pelakauto.nativeapp

import androidx.activity.compose.BackHandler
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    api: PelakApi,
    session: PelakSession,
    onNavigate: (Int) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val email = session.email
    var page by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var notificationAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationAllowed = granted }
    val entries = listOf(
        AccountEntry("آگهی‌های من", Icons.Outlined.ListAlt, "mine"),
        AccountEntry("نمایشگاه مجازی", Icons.Outlined.Storefront, "dealer"),
        AccountEntry("نشان‌ها", Icons.Outlined.BookmarkBorder, "favorites"),
        AccountEntry("بازدیدهای اخیر", Icons.Outlined.History, "recent"),
        AccountEntry("اشتراک‌گذاری پلاک", Icons.Outlined.Share, "share"),
        AccountEntry("تنظیمات", Icons.Outlined.Settings, "settings"),
        AccountEntry("امنیت و گزارش تخلف", Icons.Outlined.Security, "safety"),
        AccountEntry("اخبار و اطلاعیه‌های پلاک", Icons.Outlined.Article, "news"),
        AccountEntry("راهنما و پشتیبانی", Icons.Outlined.HelpOutline, "help"),
        AccountEntry("درباره پلاک", Icons.Outlined.Info, "about")
    )
    val currentPage = page
    if (currentPage != null) {
        BackHandler { page = null }
        val title = entries.first { it.action == currentPage }.title
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { page = null }) { Text("بازگشت") }
            }
            HorizontalDivider()
            Box(Modifier.weight(1f)) {
                when (currentPage) {
                    "dealer" -> PelakShowroomPage(api, session)
                    "help" -> PelakSupportPage(api, session)
                    "news" -> PelakNewsPage()
                    "safety" -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("برای گزارش یک آگهی، جزئیات آن را باز کن و «گزارش آگهی» را بزن.")
                        Text("پیش از بازدید و بررسی مدارک، پول یا کد تأیید در اختیار فروشنده قرار نده.")
                        OutlinedButton(onClick = { page = null; onNavigate(0) }) { Text("رفتن به آگهی‌ها") }
                        TextButton(onClick = { page = "help" }) { Text("پیام به پشتیبانی") }
                    }
                }
            }
        }
        return
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
        if (Build.VERSION.SDK_INT >= 33) item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("اعلان‌های پلاک", style = MaterialTheme.typography.titleMedium)
                Text(if (notificationAllowed) "مجوز نمایش اعلان‌ها فعال است."
                    else "برای دیدن پیام‌ها و اطلاعیه‌ها، مجوز اعلان را فعال کن.",
                    style = MaterialTheme.typography.bodySmall)
                if (!notificationAllowed) {
                    OutlinedButton(onClick = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }) { Text("فعال‌کردن اعلان‌ها") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + context.packageName)))
                    }) { Text("تنظیمات اعلان در گوشی") }
                }
            }
            HorizontalDivider()
        }
        items(entries.size) { index ->
            val entry = entries[index]
            Row(
                Modifier.fillMaxWidth().clickable {
                    when (entry.action) {
                        "mine" -> onNavigate(3)
                        "favorites" -> onNavigate(6)
                        "recent" -> onNavigate(7)
                        "share" -> {
                            val message = "پلاک؛ خودروی بعدی تو، اینجاست.\nhttps://pelakauto.com"
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message)
                            }, "اشتراک‌گذاری پلاک"))
                        }
                        "settings" -> showSettings = true
                        "help", "news", "safety", "dealer" -> page = entry.action
                        "about" -> dialog = entry.action
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
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("تنظیمات پلاک") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("حساب کاربری", fontWeight = FontWeight.Bold)
                    Text(email, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("اعلان‌ها", fontWeight = FontWeight.Bold)
                    Text("مجوز اعلان‌ها از تنظیمات گوشی مدیریت می‌شود.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val intent = if (Build.VERSION.SDK_INT >= 26) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + context.packageName))
                        }
                        context.startActivity(intent)
                    }) { Text("تنظیمات اعلان‌های گوشی") }
                    HorizontalDivider()
                    Text("زبان", fontWeight = FontWeight.Bold)
                    Text("فارسی · زبان‌های دیگر در دست توسعه", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("ظاهر", fontWeight = FontWeight.Bold)
                    Text("حالت تاریک در نسخه‌های بعدی اضافه می‌شود.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("نسخه برنامه: " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall)
                    NativeUpdateChecker()
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("بستن") } }
        )
    }
    if (dialog != null) {
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("درباره پلاک") },
            text = { Text("پلاک؛ خودروی بعدی تو، اینجاست.\nنسخهٔ اندروید " + BuildConfig.VERSION_NAME) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("بستن") } }
        )
    }
}
