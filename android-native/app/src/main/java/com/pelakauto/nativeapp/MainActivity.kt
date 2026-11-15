package com.pelakauto.nativeapp

import android.content.ActivityNotFoundException
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal

private val Navy = Color(0xFF10233F)
private val Gold = Color(0xFFC9A158)
private val Pale = Color(0xFFF6F8FB)
private val Ink = Color(0xFF172940)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 35, 63)
        runCatching { PelakPush.initialize(applicationContext) }
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Navy,
                        secondary = Gold,
                        background = Pale,
                        surface = Color.White,
                        onPrimary = Color.White,
                        onSurface = Ink
                    )
                ) { PelakNativeApp() }
            }
        }
    }
}

private data class ChatTarget(val listingId: String, val peerId: String, val title: String)

private val nav = listOf(
    "آگهی‌ها" to "🚘",
    "جست‌وجو" to "⌕",
    "ثبت آگهی" to "＋",
    "آگهی‌های من" to "▣",
    "حساب من" to "♙",
    "پیام‌ها" to "✉"
)

@Composable
private fun PelakNativeApp() {
    val api = remember { PelakApi() }
    val context = LocalContext.current
    val store = remember { PelakSessionStore(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Car>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mineLoading by remember { mutableStateOf(false) }
    var mineHasMore by remember { mutableStateOf(false) }
    var mineMoreLoading by remember { mutableStateOf(false) }
    var mineMoreError by remember { mutableStateOf<String?>(null) }
    var mineError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Car?>(null) }
    var reporting by remember { mutableStateOf<Car?>(null) }
    var session by remember { mutableStateOf<PelakSession?>(store.load()) }
    var editing by remember { mutableStateOf<Car?>(null) }
    var chatTarget by remember { mutableStateOf<ChatTarget?>(null) }
    var searched by remember { mutableStateOf<List<Car>>(emptyList()) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var moreLoading by remember { mutableStateOf(false) }
    var searchHasMore by remember { mutableStateOf(true) }
    var homeHasMore by remember { mutableStateOf(true) }
    fun saveSession(value: PelakSession) {
        store.save(value)
        session = value
    }
    LaunchedEffect(session?.refreshToken) {
        while (session != null) {
            val current = session ?: break
            delay((current.expiresAtMs - System.currentTimeMillis() - 180000L)
                .coerceIn(0L, 45L * 60L * 1000L))
            val before = session ?: break
            try { saveSession(api.refreshSession(before)) }
            catch (_: Exception) { delay(30000L) }
        }
    }
    LaunchedEffect(session?.accessToken) {
        val active = session ?: return@LaunchedEffect
        PelakPush.token(context) { token ->
            if (!token.isNullOrBlank()) scope.launch {
                try { if (session?.userId == active.userId) api.registerPush(active, token) }
                catch (_: Exception) { /* FCM delivery may not be configured server-side yet. */ }
            }
        }
    }
    LaunchedEffect(tab, search) {
        if (tab == 1) {
            delay(320)
            searchLoading = true
            searchError = null
            try {
                searched = api.published(search)
                searchHasMore = searched.size == 100
            } catch (_: Exception) { searchError = "خطای جست‌وجوی سرور؛ دوباره تلاش کن." }
            finally { searchLoading = false }
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try { cars = api.published(); homeHasMore = cars.size == 100 }
            catch (_: Exception) { error = "دریافت آگهی‌ها ممکن نشد. اینترنت را بررسی کن." }
            finally { loading = false }
        }
    }
    fun refreshMine(current: PelakSession) {
        scope.launch {
            mineLoading = true
            try {
                val result = api.mine(current)
                if (session?.userId == current.userId) {
                    mine = result; mineHasMore = result.size == 100
                    mineError = null; mineMoreError = null
                }
            }
            catch (_: Exception) {
                if (session?.userId == current.userId) mineError = "دریافت آگهی‌های من ناموفق بود. دوباره تلاش کن."
            }
            finally { mineLoading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(tab, session) {
        val current = session
        if (tab == 3 && current != null) refreshMine(current)
    }

    Scaffold(
        containerColor = Pale,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("پلاک | PELAK", color = Navy, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text("خودروی بعدی تو، اینجاست.", fontSize = 11.sp, color = Color.Gray)
                }
                Text("نسخه بومی آزمایشی", color = Navy, fontSize = 11.sp)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                nav.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            if (index == 2 && tab != 2) editing = null
                            tab = index
                        },
                        icon = { Text(item.second, fontSize = 21.sp) },
                        label = { Text(item.first, fontSize = 9.sp, maxLines = 1) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0, 1 -> {
                    val filtered = if (tab == 0) cars else searched
                    ListingsPanel(
                        cars = filtered,
                        loading = if (tab == 0) loading else searchLoading,
                        error = if (tab == 0) error else searchError,
                        search = search,
                        searching = tab == 1,
                        session = session,
                        hasMore = if (tab == 0) homeHasMore else searchHasMore,
                        moreLoading = moreLoading,
                        onLoadMore = {
                            if (!moreLoading) scope.launch {
                                moreLoading = true
                                try {
                                    val start = if (tab == 0) cars.size else searched.size
                                    val next = api.published(if (tab == 0) "" else search, start)
                                    if (tab == 0) { cars = (cars + next).distinctBy { it.id }; homeHasMore = next.size == 100 }
                                    else { searched = (searched + next).distinctBy { it.id }; searchHasMore = next.size == 100 }
                                } catch (_: Exception) {
                                    android.widget.Toast.makeText(context, "دریافت آگهی‌های بیشتر ناموفق بود؛ دوباره تلاش کن.", android.widget.Toast.LENGTH_SHORT).show()
                                } finally { moreLoading = false }
                            }
                        },
                        onSearchChange = { search = it },
                        onRefresh = {
                            if (tab == 0) refresh()
                            else scope.launch {
                                searchLoading = true
                                try { searched = api.published(search); searchHasMore = searched.size == 100; searchError = null }
                                catch (_: Exception) { searchError = "جست‌وجو ناموفق بود." }
                                finally { searchLoading = false }
                            }
                        },
                        onSelect = { selected = it },
                        onPost = { editing = null; tab = 2 }
                    )
                }
                2 -> {
                    val current = session
                    if (current == null) LoginRequired(onGoToLogin = { tab = 4 })
                    else DraftPanel(api = api, session = current, editing = editing, onSaved = {
                        editing = null
                        tab = 3
                        refreshMine(current)
                    })
                }
                3 -> {
                    if (session == null) LoginRequired(onGoToLogin = { tab = 4 })
                    else ListingsPanel(
                        cars = mine, loading = mineLoading, error = mineError,
                        search = "", searching = false, onSearchChange = {}, session = session,
                        onRefresh = { session?.let(::refreshMine) },
                        hasMore = mineHasMore,
                        moreLoading = mineMoreLoading,
                        moreError = mineMoreError,
                        onLoadMore = {
                            val active = session
                            if (active != null && !mineMoreLoading) scope.launch {
                                mineMoreLoading = true
                                try {
                                    val next = api.mine(active, mine.size)
                                    if (session?.userId == active.userId) {
                                        mine = (mine + next).distinctBy { it.id }
                                        mineHasMore = next.size == 100
                                        mineMoreError = null
                                    }
                                } catch (_: Exception) {
                                    mineMoreError = "دریافت آگهی‌های بیشتر ناموفق بود؛ دوباره تلاش کن."
                                } finally { mineMoreLoading = false }
                            }
                        },
                        onSelect = { selected = it }, onPost = { editing = null; tab = 2 },
                        title = "آگهی‌های من"
                    )
                }
                4 -> AccountPanel(
                    api = api,
                    session = session,
                    onLogin = { saveSession(it); tab = 3 },
                    onLogout = {
                        val current = session
                        session = null; store.clear(); mine = emptyList(); mineHasMore = false; mineError = null; mineMoreError = null; tab = 0
                        if (current != null) scope.launch {
                            val token = PelakPush.lastToken(context)
                            if (!token.isNullOrBlank()) runCatching { api.unregisterPush(current, token) }
                            PelakPush.discardLocalToken(context)
                        }
                    }
                )
                else -> if (session == null) LoginRequired(onGoToLogin = { tab = 4 })
                else ChatInbox(api, session!!, onThread = { chatTarget = it })
            }
        }
    }

    selected?.let { car ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(car.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (car.imagePaths.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        car.imagePaths.forEach { path ->
                            CarPhoto(path, car.title, if (car.userId == session?.userId) session?.accessToken else null,
                                Modifier.size(width = 210.dp, height = 140.dp))
                        }
                    }
                    Text(car.make + "  " + car.model + "  " + car.trim)
                    Text("سال ساخت: " + car.year + "  •  " + car.country + "، " + car.city)
                    car.mileageKm?.let { Text("کارکرد: " + it + " کیلومتر") }
                    Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold)
                    if (car.listingStatus != "published") Text("وضعیت: " + car.listingStatus)
                    if (car.description.isNotBlank()) Text(car.description)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    if (car.userId == session?.userId && car.listingStatus == "draft") {
                        Button(onClick = { editing = car; selected = null; tab = 2 }) {
                            Text("ویرایش پیش‌نویس")
                        }
                    } else if (car.listingStatus == "published") {
                        if (session != null && car.userId != session?.userId) {
                            Button(onClick = {
                                chatTarget = ChatTarget(car.id, car.userId, car.title)
                                selected = null
                            }) { Text("چت پلاک") }
                        }
                        if (car.sellerPhone.isNotBlank()) {
                            TextButton(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(
                                        "tel:" + car.sellerPhone.filter { it.isDigit() || it == '+' })))
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "برنامه تماس روی دستگاه موجود نیست.", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("تماس") }
                        }
                        if (car.sellerPhone.isNotBlank()) {
                            TextButton(onClick = {
                                val phone = car.sellerPhone.filter { it.isDigit() || it == '+' }
                                try {
                                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                                        putExtra("sms_body", "سلام، درباره آگهی " + car.title + " در پلاک پیام می‌دهم.")
                                    })
                                } catch (_: ActivityNotFoundException) {
                                    Toast.makeText(context, "برنامه پیامک روی دستگاه موجود نیست.", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("پیامک") }
                        }
                        if (session != null && car.userId != session?.userId) {
                            TextButton(onClick = { reporting = car; selected = null }) { Text("گزارش آگهی") }
                        }
                        TextButton(onClick = {
                            val url = "https://pelakauto.com/" // Deep-link route requires website support before enabling
                            val message = car.title + "\n" + car.priceLabel() + "\n" + url
                            try {
                                context.startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, message)
                                    }, "اشتراک‌گذاری آگهی"
                                ))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, "برنامه‌ای برای اشتراک‌گذاری موجود نیست.", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("اشتراک‌گذاری") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("بازگشت") }
            }
        )
    }
    reporting?.let { car ->
        val current = session
        if (current != null) ReportListingDialog(api, current, car, onClose = { reporting = null })
    }
    if (chatTarget != null && session != null) {
        ChatDialog(api, session!!, chatTarget!!, onClose = { chatTarget = null })
    }
}

@Composable
private fun ReportListingDialog(api: PelakApi, session: PelakSession, car: Car, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val reasons = listOf(
        "incorrect" to "مشخصات نادرست",
        "wrong_photos" to "عکس‌های نامرتبط",
        "fraud" to "احتمال کلاهبرداری",
        "duplicate" to "آگهی تکراری",
        "suspicious_contact" to "اطلاعات تماس مشکوک",
        "other" to "سایر موارد"
    )
    var reason by remember(car.id) { mutableStateOf(reasons.first().first) }
    var details by remember(car.id) { mutableStateOf("") }
    var busy by remember(car.id) { mutableStateOf(false) }
    var error by remember(car.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("گزارش آگهی") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(car.title, fontWeight = FontWeight.Bold)
                reasons.forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == key, onClick = { if (!busy) reason = key })
                        Text(label, modifier = Modifier.clickable(enabled = !busy) { reason = key })
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(1200) },
                    label = { Text("شرح مشکل (۱۰ تا ۱۲۰۰ نویسه)") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = !busy && details.trim().length in 10..1200, onClick = {
                scope.launch {
                    busy = true
                    error = ""
                    try {
                        api.reportListing(session, car.id, reason, details)
                        onClose()
                    } catch (_: Exception) {
                        error = "ثبت گزارش ناموفق بود؛ اتصال یا محدودیت گزارش را بررسی کن."
                    } finally { busy = false }
                }
            }) { Text(if (busy) "در حال ارسال…" else "ارسال گزارش") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("انصراف") } }
    )
}

@Composable
private fun ListingsPanel(
    cars: List<Car>,
    loading: Boolean,
    error: String?,
    search: String,
    searching: Boolean,
    session: PelakSession? = null,
    hasMore: Boolean = false,
    moreLoading: Boolean = false,
    moreError: String? = null,
    onLoadMore: () -> Unit = {},
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Car) -> Unit,
    onPost: () -> Unit,
    title: String = "جدیدترین آگهی‌های خودرو"
) {
    var sortMode by rememberSaveable { mutableStateOf("جدیدترین") }
    val sortedCars = remember(cars, sortMode) {
        when (sortMode) {
            "ارزان‌ترین" -> cars.sortedWith(compareBy<Car> { it.price?.toBigDecimalOrNull() ?: java.math.BigDecimal("999999999999999999999") })
            "گران‌ترین" -> cars.sortedWith(compareByDescending<Car> { it.price?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO })
            "کم‌کارکردترین" -> cars.sortedWith(compareBy<Car> { it.mileageKm ?: Int.MAX_VALUE })
            else -> cars
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (searching) {
            OutlinedTextField(
                value = search, onValueChange = onSearchChange,
                label = { Text("نام برند، مدل، تیپ یا شهر") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = RoundedCornerShape(15.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Navy, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh, enabled = !loading) { Text("↻ تازه‌سازی") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("جدیدترین", "ارزان‌ترین", "گران‌ترین", "کم‌کارکردترین").forEach { option ->
                FilterChip(selected = sortMode == option, onClick = { sortMode = option }, label = { Text(option, fontSize = 12.sp) })
            }
        }
        if (hasMore && sortMode != "جدیدترین") Text("مرتب‌سازی روی آگهی‌های بارگذاری‌شده انجام می‌شود.", modifier = Modifier.padding(horizontal = 16.dp), color = Color.Gray, fontSize = 11.sp)
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (error != null) {
            EmptyPanel(error, "تلاش مجدد", onRefresh)
        } else if (!loading && cars.isEmpty()) {
            EmptyPanel(
                if (searching && search.isNotBlank())
                    "آگهی مطابق جست‌وجو پیدا نشد."
                else "فعلاً آگهی منتشرشده‌ای وجود ندارد.",
                "ثبت آگهی",
                onPost
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(sortedCars, key = { it.id }) { car ->
                    CarRow(car = car, session = session, onClick = { onSelect(car) })
                }
                if (hasMore) item {
                    if (moreError != null) Text(moreError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Button(onClick = onLoadMore, enabled = !moreLoading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (moreLoading) "در حال دریافت…" else "نمایش آگهی‌های بیشتر")
                    }
                }
            }
        }
    }
}

@Composable
private fun CarRow(car: Car, session: PelakSession?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CarPhoto(car.imagePath, car.title,
                session?.takeIf { it.userId == car.userId }?.accessToken,
                Modifier.size(110.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(car.title, fontWeight = FontWeight.Bold, maxLines = 2, color = Ink)
                Text(
                    "مدل " + car.year + " • " + car.country + " " + car.city,
                    color = Color.Gray, fontSize = 12.sp, maxLines = 1
                )
                Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                car.mileageKm?.let { Text("کارکرد " + java.text.NumberFormat.getNumberInstance().format(it) + " کیلومتر", color = Color.Gray, fontSize = 12.sp) }
                if (car.imagePaths.size > 1) Text(car.imagePaths.size.toString() + " عکس", color = Color.Gray, fontSize = 11.sp)
                if (car.listingStatus != "published") Text("وضعیت: " + car.listingStatus, color = Gold, fontSize = 11.sp)

            }
        }
    }
}

@Composable
private fun EmptyPanel(message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🚘", fontSize = 44.sp)
        Text(message, color = Color.Gray)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun LoginRequired(onGoToLogin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("برای ادامه وارد حساب پلاک شو", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        Button(onClick = onGoToLogin) { Text("ورود با کد ایمیلی") }
    }
}

@Composable
private fun AccountPanel(
    api: PelakApi,
    session: PelakSession?,
    onLogin: (PelakSession) -> Unit,
    onLogout: () -> Unit
) {
    if (session != null) {
        val context = LocalContext.current
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        var pushStatus by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("به پلاک خوش آمدی", fontWeight = FontWeight.Bold, color = Navy, fontSize = 21.sp)
            Spacer(Modifier.height(10.dp))
            Text(session.email)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onLogout) { Text("خروج از حساب در این دستگاه") }
            Spacer(Modifier.height(10.dp))
            Text("ورود به‌صورت رمزگذاری‌شده روی همین گوشی حفظ می‌شود.",
                color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(13.dp))
            Text("اعلان پیام‌ها و وضعیت آگهی‌ها", fontWeight = FontWeight.Bold)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else pushStatus = "اعلان‌های سیستم مجازند؛ ارسال واقعی به تنظیمات Firebase وابسته است."
            }) { Text("فعال‌سازی اعلان بومی") }
            if (pushStatus.isNotBlank()) Text(pushStatus, color = Color.Gray, fontSize = 12.sp)
        }
        return
    }
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var sentToEmail by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ورود / ثبت‌نام پلاک", fontWeight = FontWeight.Bold, color = Navy, fontSize = 21.sp)
        Text("با ایمیل خود کد ورود یک‌بارمصرف دریافت کن.", color = Color.Gray)
        OutlinedTextField(value = email, onValueChange = {
            email = it
            if (sent && it.trim() != sentToEmail) { sent = false; otp = ""; sentToEmail = "" }
        },
            label = { Text("ایمیل") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth())
        if (sent) {
            OutlinedTextField(value = otp, onValueChange = { otp = it.filter(Char::isDigit).take(6) },
                label = { Text("کد ۶ رقمی دریافتی") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
        }
        Button(
            enabled = !busy && email.trim().contains("@") && (!sent || (otp.length == 6 && email.trim() == sentToEmail)),
            onClick = {
                scope.launch {
                    busy = true; info = ""
                    try {
                        if (!sent) {
                            api.sendOtp(email)
                            sentToEmail = email.trim()
                            sent = true
                            info = "کد به ایمیلت ارسال شد."
                        } else {
                            onLogin(api.verifyOtp(sentToEmail, otp))
                            otp = ""
                        }
                    } catch (_: Exception) {
                        info = "عملیات ورود ناموفق بود؛ ایمیل، کد یا اتصال را بررسی کن."
                    } finally { busy = false }
                }
            }
        ) { Text(if (busy) "در حال بررسی…" else if (sent) "تأیید و ورود" else "دریافت کد") }
        if (sent) TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true; info = ""
                try {
                    api.sendOtp(sentToEmail)
                    otp = ""
                    info = "درخواست ارسال مجدد ثبت شد؛ ایمیل را بررسی کن."
                } catch (_: Exception) {
                    info = "ارسال مجدد ناموفق بود؛ کمی بعد دوباره تلاش کن."
                } finally { busy = false }
            }
        }) { Text("ارسال مجدد کد") }
        if (info.isNotBlank()) Text(info, color = Navy)
    }
}

@Composable
private fun DraftPanel(api: PelakApi, session: PelakSession, editing: Car?, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var make by rememberSaveable(editing?.id) { mutableStateOf(editing?.make.orEmpty()) }
    var model by rememberSaveable(editing?.id) { mutableStateOf(editing?.model.orEmpty()) }
    var year by rememberSaveable(editing?.id) { mutableStateOf(editing?.year?.toString().orEmpty()) }
    var city by rememberSaveable(editing?.id) { mutableStateOf(editing?.city.orEmpty()) }
    var country by rememberSaveable(editing?.id) { mutableStateOf(editing?.country ?: "ایران") }
    var phone by rememberSaveable(editing?.id) { mutableStateOf(editing?.sellerPhone.orEmpty()) }
    var price by rememberSaveable(editing?.id) {
        mutableStateOf(
            if (editing?.currency == "IRR" && editing.price != null)
                runCatching { BigDecimal(editing.price).divide(BigDecimal.TEN).toPlainString() }.getOrDefault("")
            else ""
        )
    }
    var negotiable by rememberSaveable(editing?.id) { mutableStateOf(editing?.priceNegotiable ?: false) }
    var photoUris by remember(editing?.id) { mutableStateOf<List<Uri>>(emptyList()) }
    var savedPhotoPaths by remember(editing?.id) { mutableStateOf(editing?.imagePaths.orEmpty()) }
    var uploadedUris by remember(editing?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var draftId by remember(editing?.id) { mutableStateOf(editing?.id) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selected ->
        val space = (8 - savedPhotoPaths.size).coerceAtLeast(0)
        photoUris = (photoUris + selected).distinct().take(space)
    }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(if (editing == null) "ثبت پیش‌نویس آگهی" else "ویرایش پیش‌نویس",
                fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("ثبت مستقیم در Supabase؛ انتشار عمومی پس از بررسی مدیر انجام می‌شود.",
                fontSize = 12.sp, color = Color.Gray)
        }
        item { NativeField("عنوان آگهی", title) { title = it } }
        item { NativeField("برند خودرو", make) { make = it } }
        item { NativeField("مدل خودرو", model) { model = it } }
        item { NativeField("سال ساخت میلادی", year, KeyboardType.Number) { year = it } }
        item { NativeField("کشور", country) { country = it } }
        item { NativeField("شهر", city) { city = it } }
        item { NativeField("شماره تماس فروشنده", phone, KeyboardType.Phone) { phone = it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = negotiable, onCheckedChange = { negotiable = it })
                Spacer(Modifier.width(8.dp))
                Text("قیمت توافقی")
            }
        }
        if (!negotiable) item {
            NativeField("قیمت به تومان", price, KeyboardType.Number) { price = it }
        }
        item {
            Text("عکس‌های خودرو: " + savedPhotoPaths.size + " ذخیره‌شده، " +
                photoUris.count { it.toString() !in uploadedUris } + " انتخاب‌شده",
                color = Navy, fontWeight = FontWeight.Bold)
            TextButton(
                onClick = { picker.launch("image/*") },
                enabled = !working && savedPhotoPaths.size + photoUris.size < 8
            ) { Text("＋ افزودن عکس از گالری (حداکثر ۸ عکس)") }
        }
        if (photoUris.isNotEmpty()) item {
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photoUris.forEach { uri ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(model = uri, contentDescription = "عکس خودرو",
                            modifier = Modifier.size(100.dp))
                        TextButton(onClick = { photoUris = photoUris - uri }, enabled = !working) {
                            Text("حذف", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                onClick = {
                    val y = year.toIntOrNull()
                    val p = price.replace(",", "").trim().toBigDecimalOrNull()
                    if (title.isBlank() || make.isBlank() || model.isBlank() ||
                        y == null || y !in 1950..2100 || country.isBlank() ||
                        phone.isBlank() || (!negotiable && (p == null || p <= BigDecimal.ZERO))) {
                        info = "عنوان، برند، مدل، سال، کشور، شماره تماس و قیمت را درست وارد کن."
                    } else {
                        scope.launch {
                            working = true; info = ""
                            try {
                                val rials = if (negotiable) null else
                                    p!!.multiply(BigDecimal.TEN).toPlainString()
                                val savedId = draftId
                                val id = if (savedId == null) {
                                    api.createDraft(session, title, make, model, y, rials, country, city, phone)
                                        .also { draftId = it }
                                } else {
                                    api.updateDraft(session, savedId, title, make, model, y, rials,
                                        country, city, phone)
                                    savedId
                                }
                                for (uri in photoUris) {
                                    if (uri.toString() in uploadedUris) continue
                                    val path = api.uploadPhoto(session, context.contentResolver, uri)
                                    val next = savedPhotoPaths + path
                                    try { api.attachPhotos(session, id, next) }
                                    catch (failure: Exception) {
                                        runCatching { api.removeUnattachedPhoto(session, path) }
                                        throw failure
                                    }
                                    savedPhotoPaths = next
                                    uploadedUris = uploadedUris + uri.toString()
                                }
                                onSaved()
                            } catch (_: Exception) {
                                info = "ذخیره کامل نشد. پیش‌نویس ثبت‌شده محفوظ است؛ برای عکس‌ها دوباره تلاش کن."
                            } finally { working = false }
                        }
                    }
                }
            ) { Text(if (working) "در حال ذخیره و آپلود…" else "ذخیره پیش‌نویس و عکس‌ها") }
        }
        item { if (info.isNotBlank()) Text(info, color = Navy) }
        item {
            Text("انتشار و بازبینی عکس‌ها همچنان طبق قوانین مدیر انجام می‌شود.",
                color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NativeField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun CarPhoto(path: String?, title: String, jwt: String?, modifier: Modifier = Modifier) {
    val api = remember { PelakApi() }
    val url by produceState<String?>(null, path, jwt) {
        value = path?.let { api.signedPhoto(it, jwt) }
    }
    Box(modifier.background(Pale, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center) {
        if (url == null) Text("🚘", fontSize = 27.sp)
        else AsyncImage(
            model = url, contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
}

@Composable
private fun ChatInbox(api: PelakApi, session: PelakSession, onThread: (ChatTarget) -> Unit) {
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.userId, session.accessToken, reload) {
        busy = true
        try { messages = api.inbox(session); error = null }
        catch (_: Exception) { error = "دریافت پیام‌ها ممکن نشد" }
        finally { busy = false }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("پیام‌های پلاک", color = Navy, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            TextButton(onClick = { reload++ }) { Text("تازه‌سازی") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) Text(error.orEmpty(), color = Color.Gray)
        val threads = messages.distinctBy {
            it.listingId + ":" + if (it.senderId == session.userId) it.recipientId else it.senderId
        }
        if (!busy && threads.isEmpty()) Text("هنوز گفت‌وگویی نداری.", color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(threads, key = { it.id }) { msg ->
                val peer = if (msg.senderId == session.userId) msg.recipientId else msg.senderId
                Card(
                    modifier = Modifier.fillMaxWidth().clickable {
                        onThread(ChatTarget(msg.listingId, peer, "گفت‌وگوی آگهی"))
                    },
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(15.dp)) {
                        Text("گفت‌وگوی آگهی • " + msg.listingId.take(8),
                            color = Navy, fontWeight = FontWeight.Bold)
                        Text(msg.body, maxLines = 2, color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDialog(api: PelakApi, session: PelakSession, target: ChatTarget, onClose: () -> Unit) {
    var messages by remember(target.listingId, target.peerId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var body by remember(target.listingId, target.peerId) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target.listingId, target.peerId, session.accessToken, reload) {
        try { messages = api.thread(session, target.listingId, target.peerId); error = null }
        catch (_: Exception) { error = "خواندن پیام‌ها ممکن نشد." }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(target.title, maxLines = 1, color = Navy) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 310.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (messages.isEmpty()) Text("برای شروع گفت‌وگو پیام بنویس.", color = Color.Gray)
                    messages.forEach { msg ->
                        Text(
                            (if (msg.senderId == session.userId) "من: " else "طرف مقابل: ") + msg.body,
                            color = Ink,
                            modifier = Modifier.fillMaxWidth().background(
                                if (msg.senderId == session.userId) Pale else Color.White,
                                RoundedCornerShape(9.dp)).padding(8.dp),
                            fontSize = 13.sp
                        )
                    }
                }
                OutlinedTextField(body, { body = it.take(1500) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("متن پیام") },
                    maxLines = 4)
                if (error != null) Text(error.orEmpty(), color = Color.Red, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    working = true
                    try {
                        api.sendMessage(session, target.listingId, target.peerId, body)
                        body = ""; reload++
                    } catch (_: Exception) { error = "ارسال انجام نشد؛ دسترسی یا اینترنت را بررسی کن." }
                    finally { working = false }
                }
            }, enabled = !working && body.trim().isNotEmpty()) {
                Text(if (working) "در حال ارسال…" else "ارسال پیام")
            }
        },
        dismissButton = {
            TextButton(onClick = { reload++ }) { Text("↻ تازه‌سازی") }
            TextButton(onClick = onClose) { Text("بستن") }
        }
    )
}
