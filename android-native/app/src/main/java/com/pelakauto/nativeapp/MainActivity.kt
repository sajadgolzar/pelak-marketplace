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
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
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

internal data class NotificationRoute(val kind: String, val sourceId: String, val event: Long)

class MainActivity : ComponentActivity() {
    private var notification by mutableStateOf<NotificationRoute?>(null)
    private fun readNotification(intent: Intent?) {
        val kind = intent?.getStringExtra("pelak_kind") ?: return
        val source = intent.getStringExtra("pelak_source").orEmpty()
        notification = NotificationRoute(kind, source, System.nanoTime())
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readNotification(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readNotification(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(
            android.graphics.Color.WHITE, android.graphics.Color.WHITE))
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
                ) { PelakNativeApp(notification) }
            }
        }
    }
}

private fun revisionDeadlineMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(raw).toEpochMilli() }
        .getOrNull()
}

private data class ChatTarget(val listingId: String, val peerId: String, val title: String)

// Visible navigation is five tabs; internal routes 1, 3 and 7 remain accessible.
private val nav = listOf(
    Triple("آگهی‌ها", "🚘", 0),
    Triple("نشان‌ها", "☆", 6),
    Triple("ثبت آگهی", "＋", 2),
    Triple("چت", "✉", 5),
    Triple("پلاک من", "♙", 4)
)

@Composable
private fun PelakNativeApp(notification: NotificationRoute?) {
    val api = remember { PelakApi() }
    val context = LocalContext.current
    val store = remember { PelakSessionStore(context) }
    val recentStore = remember { PelakRecentViews(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Car>>(emptyList()) }
    var revisionReasons by remember { mutableStateOf<Map<String, PelakApi.RevisionNotice>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var mineLoading by remember { mutableStateOf(false) }
    var mineHasMore by remember { mutableStateOf(false) }
    var mineMoreLoading by remember { mutableStateOf(false) }
    var mineMoreError by remember { mutableStateOf<String?>(null) }
    var mineError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var publicFilters by remember { mutableStateOf(ListingFilters()) }
    val searchPrefs = remember { context.getSharedPreferences("pelak_native_search_history", android.content.Context.MODE_PRIVATE) }
    var searchHistory by remember {
        mutableStateOf(searchPrefs.getString("recent_queries", "").orEmpty().split("\n")
            .filter { it.isNotBlank() }.take(8))
    }
    fun saveSearchQuery(query: String) {
        val clean = query.trim().replace("\n", " ").take(80)
        if (clean.length < 2) return
        val updated = (listOf(clean) + searchHistory.filterNot { it.equals(clean, ignoreCase = true) }).take(8)
        searchHistory = updated
        searchPrefs.edit().putString("recent_queries", updated.joinToString("\n")).apply()
    }
    var selected by remember { mutableStateOf<Car?>(null) }
    var expandedPhoto by remember { mutableStateOf<Pair<Car, Int>?>(null) }
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
    var favorites by remember { mutableStateOf<List<Car>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var recentCars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var recentLoading by remember { mutableStateOf(false) }
    var recentError by remember { mutableStateOf<String?>(null) }
    var recentRevision by remember { mutableIntStateOf(0) }
    var favoritesLoading by remember { mutableStateOf(false) }
    var favoritesError by remember { mutableStateOf<String?>(null) }
    var favoriteBusy by remember { mutableStateOf(false) }
    var ownerActionBusy by remember { mutableStateOf(false) }
    var ownerActionConfirm by remember { mutableStateOf<Pair<Car, String>?>(null) }

    fun refreshRecent() {
        scope.launch {
            recentLoading = true
            recentError = null
            try {
                val rows = api.publishedByIds(recentStore.ids(session?.userId))
                recentCars = rows
            } catch (_: Exception) {
                recentError = "دریافت بازدیدهای اخیر ناموفق بود؛ دوباره تلاش کن."
            } finally { recentLoading = false }
        }
    }
    fun refreshFavorites(current: PelakSession) {
        scope.launch {
            favoritesLoading = true
            favoritesError = null
            try {
                val ids = api.favoriteIds(current)
                val rows = api.favoriteCars(current)
                if (session?.userId == current.userId) {
                    favoriteIds = ids
                    favorites = rows
                }
            } catch (_: Exception) {
                favoritesError = "دریافت نشان‌ها ناموفق بود؛ دوباره تلاش کن."
            } finally { favoritesLoading = false }
        }
    }

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
            try {
                val renewed = api.refreshSession(before)
                // Never restore a session after logout or an account switch.
                if (session?.userId == before.userId && session?.refreshToken == before.refreshToken) {
                    saveSession(renewed)
                }
            } catch (_: Exception) {
                delay(30000L)
            }
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
    // Save only settled searches, not every intermediate character while typing.
    LaunchedEffect(tab, search) {
        if (tab == 1 && search.trim().length >= 2) {
            delay(1500)
            if (tab == 1) saveSearchQuery(search)
        }
    }
    LaunchedEffect(tab, search, publicFilters) {
        if (tab == 1) {
            searchLoading = true
            val requestedSearch = search
            delay(320)
            searchError = null
            try {
                val results = api.published(requestedSearch, filters = publicFilters)
                if (tab == 1 && search == requestedSearch) {
                    searched = results
                    searchHasMore = results.size == 100
                }
            } catch (_: Exception) {
                if (tab == 1 && search == requestedSearch)
                    searchError = "خطای جست‌وجوی سرور؛ دوباره تلاش کن."
            } finally {
                if (tab == 1 && search == requestedSearch) searchLoading = false
            }
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try { cars = api.published(filters = publicFilters); homeHasMore = cars.size == 100 }
            catch (_: Exception) { error = "دریافت آگهی‌ها ممکن نشد. اینترنت را بررسی کن." }
            finally { loading = false }
        }
    }
    fun refreshMine(current: PelakSession) {
        scope.launch {
            mineLoading = true
            try {
                val result = api.mine(current)
                val reasons = runCatching { api.myRevisionReasons(current) }.getOrDefault(emptyMap())
                if (session?.userId == current.userId) {
                    revisionReasons = reasons
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

    LaunchedEffect(selected?.id, session?.userId) {
        selected?.let {
            if (it.listingStatus == "published") {
                recentStore.record(session?.userId, it.id)
                recentRevision++
            }
        }
    }
    LaunchedEffect(tab, session?.userId, recentRevision) {
        if (tab == 7) refreshRecent()
    }
    LaunchedEffect(session?.userId) {
        favoriteIds = emptySet()
        favorites = emptyList()
        session?.let { current ->
            try { favoriteIds = api.favoriteIds(current) } catch (_: Exception) {}
        }
    }
    LaunchedEffect(publicFilters) { refresh() }
    LaunchedEffect(tab, session?.userId) {
        if (tab == 6) session?.let { refreshFavorites(it) }
    }
    LaunchedEffect(tab, session) {
        val current = session
        if (tab == 3 && current != null) refreshMine(current)
    }

    var supportMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notification?.event, session?.userId) {
        val route = notification ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        when (route.kind) {
            "listing_message" -> {
                tab = 5
                runCatching { api.messageById(active, route.sourceId) }.getOrNull()?.let { message ->
                    val peer = if (message.senderId == active.userId) message.recipientId else message.senderId
                    chatTarget = ChatTarget(message.listingId, peer, "گفتگوی آگهی")
                }
            }
            "support_message" -> { supportMessage = route.sourceId; tab = 4 }
            "listing_needs_revision", "listing_published" -> tab = 3
        }
    }

    BackHandler(enabled = tab != 0 && selected == null && expandedPhoto == null && chatTarget == null) {
        tab = if (tab in listOf(3, 6, 7)) 4 else 0
    }

    Scaffold(
        containerColor = Pale,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.mipmap.ic_launcher), contentDescription = "لوگوی PELAK",
                        modifier = Modifier.size(48.dp))
                    Column {
                        Text("پلاک", color = Navy, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("خودروی بعدی تو، اینجاست.", fontSize = 11.sp, color = Color.Gray)
                    }
                }
                NativeUpdateChecker()
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                nav.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item.third ||
                            (tab == 1 && item.third == 0) ||
                            (tab == 3 && item.third == 4) ||
                            (tab == 7 && item.third == 4),
                        onClick = {
                            if (item.third == 2 && tab != 2) editing = null
                            tab = item.third
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
                        serverFiltered = true,
                        onFiltersChanged = { publicFilters = it },
                        searchHistory = if (tab == 1) searchHistory else emptyList(),
                        onHistorySelect = { search = it },
                        onHistoryClear = {
                            searchHistory = emptyList()
                            searchPrefs.edit().remove("recent_queries").apply()
                        },
                        session = session,
                        hasMore = if (tab == 0) homeHasMore else searchHasMore,
                        moreLoading = moreLoading,
                        onLoadMore = {
                            if (!moreLoading) {
                                val requestedTab = tab
                                val requestedFilters = publicFilters
                                val requestedSearch = if (requestedTab == 0) "" else search
                                val start = if (requestedTab == 0) cars.size else searched.size
                                scope.launch {
                                    moreLoading = true
                                    try {
                                        val next = api.published(requestedSearch, start, filters = requestedFilters)
                                        // Ignore a stale response if the user switched tabs or changed the query.
                                        if (tab == requestedTab && publicFilters == requestedFilters && (requestedTab == 0 || search == requestedSearch)) {
                                            if (requestedTab == 0) {
                                                cars = (cars + next).distinctBy { it.id }
                                                homeHasMore = next.size == 100
                                            } else {
                                                searched = (searched + next).distinctBy { it.id }
                                                searchHasMore = next.size == 100
                                            }
                                        }
                                    } catch (_: Exception) {
                                        if (tab == requestedTab && publicFilters == requestedFilters && (requestedTab == 0 || search == requestedSearch))
                                            android.widget.Toast.makeText(context, "دریافت آگهی‌های بیشتر ناموفق بود؛ دوباره تلاش کن.", android.widget.Toast.LENGTH_SHORT).show()
                                    } finally { moreLoading = false }
                                }
                            }
                        },
                        onSearchChange = { search = it },
                        onOpenSearch = { tab = 1 },
                        onRefresh = {
                            if (tab == 0) refresh()
                            else scope.launch {
                                searchLoading = true
                                try { searched = api.published(search, filters = publicFilters); searchHasMore = searched.size == 100; searchError = null }
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
                7 -> {
                    ListingsPanel(
                        cars = recentCars,
                        loading = recentLoading,
                        error = recentError,
                        search = "",
                        searching = false,
                        session = session,
                        onSearchChange = {},
                        onRefresh = { refreshRecent() },
                        onSelect = { selected = it },
                        onPost = { tab = 0 },
                        title = "بازدیدهای اخیر"
                    )
                }
                6 -> {
                    val current = session
                    if (current == null) LoginRequired(onGoToLogin = { tab = 4 })
                    else ListingsPanel(
                        cars = favorites,
                        loading = favoritesLoading,
                        error = favoritesError,
                        search = "",
                        searching = false,
                        session = current,
                        onSearchChange = {},
                        onRefresh = { refreshFavorites(current) },
                        onSelect = { selected = it },
                        onPost = { tab = 0 },
                        title = "نشان‌های من"
                    )
                }
                4 -> AccountPanel(
                    api = api,
                    session = session,
                    onLogin = { saveSession(it); tab = 4; refreshMine(it) },
                    onNavigate = { tab = it },
                    supportMessage = supportMessage,
                    onLogout = {
                        val current = session
                        session = null; store.clear(); mine = emptyList(); revisionReasons = emptyMap();
                        favoriteIds = emptySet(); favorites = emptyList();
                        mineHasMore = false; mineError = null; mineMoreError = null; tab = 0
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

    expandedPhoto?.let { (photoCar, photoIndex) ->
        Dialog(onDismissRequest = { expandedPhoto = null }) {
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(photoCar.title, fontWeight = FontWeight.Bold, color = Navy)
                PhotoGallery(photoCar.imagePaths, photoCar.title,
                    if (photoCar.userId == session?.userId) session?.accessToken else null, photoIndex)
                TextButton(onClick = { expandedPhoto = null }) { Text("بستن") }
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
                        car.imagePaths.forEachIndexed { index, path ->
                            CarPhoto(path, car.title, if (car.userId == session?.userId) session?.accessToken else null,
                                Modifier.size(width = 210.dp, height = 140.dp)
                                    .clickable { expandedPhoto = car to index })
                        }
                    }
                    Text(car.make + "  " + car.model + "  " + car.trim)
                    Text("سال ساخت: " + car.year + "  •  " + car.country + "، " + car.city)
                    car.mileageKm?.let { Text("کارکرد: " + it + " کیلومتر") }
                    if (session != null && car.listingStatus == "published") {
                        TextButton(
                            enabled = !favoriteBusy,
                            onClick = {
                                val active = session ?: return@TextButton
                                val saved = car.id in favoriteIds
                                scope.launch {
                                    favoriteBusy = true
                                    try {
                                        api.setFavorite(active, car.id, !saved)
                                        if (session?.userId == active.userId) {
                                            favoriteIds = if (saved) favoriteIds - car.id else favoriteIds + car.id
                                            if (tab == 6) refreshFavorites(active)
                                        }
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "ذخیره نشان ناموفق بود؛ دوباره تلاش کن.", Toast.LENGTH_SHORT).show()
                                    } finally { favoriteBusy = false }
                                }
                            }
                        ) { Text(if (car.id in favoriteIds) "★ حذف از نشان‌ها" else "☆ افزودن به نشان‌ها") }
                    }
                    Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold)
                    if (car.listingStatus != "published") Text("وضعیت: " + car.listingStatus)
                    if (car.description.isNotBlank()) Text(car.description)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    if (car.userId == session?.userId && car.listingStatus == "draft" &&
                        car.id !in revisionReasons) {
                        Button(onClick = { editing = car; selected = null; tab = 2 }) {
                            Text("ویرایش پیش‌نویس")
                        }
                        Button(enabled = car.imagePaths.isNotEmpty(), onClick = {
                            ownerActionConfirm = car to "submit"; selected = null
                        }) { Text("ارسال برای بررسی") }
                    }
                    if (car.userId == session?.userId) {
                        if (car.id in revisionReasons && car.listingStatus == "draft") {
                            val notice = revisionReasons[car.id]!!
                            Text("علت بازگشت: " + notice.reason, color = Gold)
                            notice.deleteAfter?.let { deadline ->
                                val deadlineMs = revisionDeadlineMillis(deadline)
                                if (deadlineMs != null) {
                                    val days = kotlin.math.ceil(
                                        (deadlineMs - System.currentTimeMillis()) / 86400000.0
                                    ).toInt()
                                    Text(if (days > 0) "مهلت اصلاح: $days روز باقی مانده" else
                                        "مهلت اصلاح به پایان رسیده؛ ارسال مجدد ممکن نیست.",
                                        color = if (days > 0) Gold else MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp)
                                } else {
                                    Text("آخرین مهلت اصلاح: $deadline", fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = { editing = car; selected = null; tab = 2 }) {
                                Text("اصلاح آگهی")
                            }
                            val deadlinePassed = revisionDeadlineMillis(notice.deleteAfter)
                                ?.let { it <= System.currentTimeMillis() } ?: false
                            if (!deadlinePassed) {
                                if (car.imagePaths.isNotEmpty()) {
                                    TextButton(onClick = {
                                        ownerActionConfirm = car to "resubmit"; selected = null
                                    }) {
                                        Text("ارسال مجدد برای بررسی")
                                    }
                                } else {
                                    Text("برای ارسال مجدد، ابتدا دست‌کم یک عکس خودرو ذخیره کن.",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            } else {
                                Text("مهلت تمام شده؛ آگهی و تصاویر آن در صف حذف خودکار هستند.",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (car.listingStatus == "published" || car.listingStatus == "archived") {
                            TextButton(onClick = { ownerActionConfirm = car to "renew"; selected = null }) {
                                Text("تمدید آگهی")
                            }
                        }
                        if (car.listingStatus in listOf("draft", "published", "archived")) {
                            TextButton(onClick = { ownerActionConfirm = car to "cancel"; selected = null }) {
                                Text("لغو آگهی")
                            }
                        }
                    }
                    if (car.listingStatus == "published") {
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
                            // Share the exact listing ID until the website supports a verified deep-link route.
                            val message = listOf(
                                car.title,
                                listOf(car.make, car.model, car.trim).filter { it.isNotBlank() }.joinToString(" "),
                                "سال ساخت: ${car.year}",
                                car.mileageKm?.let { "کارکرد: $it کیلومتر" }.orEmpty(),
                                "قیمت: ${car.priceLabel()}",
                                "موقعیت: ${car.city}، ${car.country}",
                                "شناسه آگهی: ${car.id}",
                                "پلاک: https://pelakauto.com/"
                            ).filter { it.isNotBlank() }.joinToString("\n")
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
    ownerActionConfirm?.let { (car, action) ->
        val current = session
        AlertDialog(
            onDismissRequest = { if (!ownerActionBusy) ownerActionConfirm = null },
            title = { Text(when (action) {
                "cancel" -> "لغو آگهی"
                "renew" -> "تمدید آگهی"
                "submit" -> "ارسال برای بررسی"
                else -> "ارسال مجدد برای بررسی"
            }) },
            text = { Text(when (action) {
                "cancel" -> "آگهی «${car.title}» لغو شود؟ ممکن است طبق سیاست پاک‌سازی پلاک، تصاویر آن بعداً حذف شوند."
                "renew" -> "درخواست تمدید آگهی «${car.title}» ثبت شود؟"
                "submit" -> "آگهی «${car.title}» برای بررسی مدیر ارسال شود؟"
                else -> "آگهی «${car.title}» پس از اصلاح برای بررسی دوباره ارسال شود؟"
            }) },
            confirmButton = {
                Button(enabled = !ownerActionBusy && current != null, onClick = {
                    val active = current ?: return@Button
                    scope.launch {
                        ownerActionBusy = true
                        try {
                            when (action) {
                                "cancel" -> api.cancelMyListing(active, car.id)
                                "renew" -> api.renewMyListing(active, car.id)
                                "resubmit" -> api.resubmitMyListing(active, car.id)
                                "submit" -> api.submitDraft(active, car.id)
                            }
                            ownerActionConfirm = null
                            refreshMine(active)
                            Toast.makeText(context, "درخواست با موفقیت ثبت شد.", Toast.LENGTH_SHORT).show()
                        } catch (_: Exception) {
                            Toast.makeText(context, "عملیات انجام نشد؛ وضعیت آگهی یا دسترسی را بررسی کن.", Toast.LENGTH_LONG).show()
                        } finally { ownerActionBusy = false }
                    }
                }) { Text(if (ownerActionBusy) "در حال انجام…" else "تأیید") }
            },
            dismissButton = {
                TextButton(enabled = !ownerActionBusy, onClick = { ownerActionConfirm = null }) {
                    Text("انصراف")
                }
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
    onOpenSearch: () -> Unit = {},
    onRefresh: () -> Unit,
    onSelect: (Car) -> Unit,
    onPost: () -> Unit,
    title: String = "جدیدترین آگهی‌های خودرو",
    serverFiltered: Boolean = false,
    onFiltersChanged: (ListingFilters) -> Unit = {},
    searchHistory: List<String> = emptyList(),
    onHistorySelect: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {}
) {
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var brandFilter by rememberSaveable { mutableStateOf("") }
    var cityFilter by rememberSaveable { mutableStateOf("") }
    var minYear by rememberSaveable { mutableStateOf("") }
    var maxYear by rememberSaveable { mutableStateOf("") }
    var minMileage by rememberSaveable { mutableStateOf("") }
    var maxMileage by rememberSaveable { mutableStateOf("") }
    var priceCurrency by rememberSaveable { mutableStateOf("IRR") }
    var minPrice by rememberSaveable { mutableStateOf("") }
    var maxPrice by rememberSaveable { mutableStateOf("") }
    var statusFilter by rememberSaveable { mutableStateOf("همه") }
    var negotiableOnly by rememberSaveable { mutableStateOf(false) }
    var photosOnly by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf("جدیدترین") }
    val requestedFilters = ListingFilters(brandFilter, cityFilter, minYear, maxYear, minMileage, maxMileage,
        minPrice, maxPrice, priceCurrency, statusFilter, negotiableOnly, photosOnly, sortMode)
    LaunchedEffect(requestedFilters, serverFiltered) {
        if (serverFiltered) { delay(350); onFiltersChanged(requestedFilters) }
    }
    val filteredCars = remember(cars, serverFiltered, brandFilter, cityFilter, minYear, maxYear,
        minMileage, maxMileage, minPrice, maxPrice, priceCurrency, statusFilter, negotiableOnly, photosOnly) {
        if (serverFiltered) cars else cars.filter { car ->
            val searchFields = listOf(car.make, car.model, car.trim, car.title).joinToString(" ")
            val amount = car.price?.toBigDecimalOrNull()
            val priceFilterActive = minPrice.isNotBlank() || maxPrice.isNotBlank()
            val priceInSelectedUnit = if (car.currency == "IRR" && priceCurrency == "IRR")
                amount?.divide(java.math.BigDecimal.TEN) else amount
            (brandFilter.isBlank() || vehicleSearchKey(brandFilter) in vehicleSearchKey(searchFields)) &&
                (cityFilter.isBlank() || listOf(car.city, car.country).joinToString(" ")
                    .contains(cityFilter.trim(), ignoreCase = true)) &&
                (minYear.toIntOrNull()?.let { car.year >= it } ?: true) &&
                (maxYear.toIntOrNull()?.let { car.year <= it } ?: true) &&
                (minMileage.toIntOrNull()?.let { (car.mileageKm ?: Int.MAX_VALUE) >= it } ?: true) &&
                (maxMileage.toIntOrNull()?.let { (car.mileageKm ?: Int.MAX_VALUE) <= it } ?: true) &&
                (!priceFilterActive || car.currency == priceCurrency) &&
                (minPrice.toBigDecimalOrNull()?.let { priceInSelectedUnit != null && priceInSelectedUnit >= it } ?: true) &&
                (maxPrice.toBigDecimalOrNull()?.let { priceInSelectedUnit != null && priceInSelectedUnit <= it } ?: true) &&
                (statusFilter == "همه" || car.vehicleStatus == statusFilter) &&
                (!negotiableOnly || car.priceNegotiable) &&
                (!photosOnly || car.imagePaths.isNotEmpty())
        }
    }
    // Compare prices only within one currency; mixed-currency feeds must not imply an FX rate.
    val sortedCars = remember(filteredCars, sortMode, priceCurrency, serverFiltered) {
        if (serverFiltered) return@remember filteredCars
        val sameCurrency = filteredCars.all { it.currency == priceCurrency }
        when (sortMode) {
            "ارزان‌ترین" -> if (sameCurrency)
                filteredCars.sortedWith(compareBy<Car> {
                    it.price?.toBigDecimalOrNull() ?: java.math.BigDecimal("999999999999999999999")
                }) else filteredCars
            "گران‌ترین" -> if (sameCurrency)
                filteredCars.sortedWith(compareByDescending<Car> {
                    it.price?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                }) else filteredCars
            "کم‌کارکردترین" -> filteredCars.sortedWith(compareBy<Car> { it.mileageKm ?: Int.MAX_VALUE })
            "جدیدترین مدل" -> filteredCars.sortedWith(compareByDescending<Car> { it.year })
            else -> filteredCars
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!searching && title == "جدیدترین آگهی‌های خودرو") {
            OutlinedButton(onClick = onOpenSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("⌕ جست‌وجوی برند، مدل، تیپ یا شهر")
            }
        }
        if (searching) {
            if (searchHistory.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("جست‌وجوهای اخیر", color = Color.Gray, fontSize = 12.sp)
                    TextButton(onClick = onHistoryClear) { Text("پاک‌کردن", fontSize = 12.sp) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    searchHistory.forEach { query ->
                        AssistChip(onClick = { onHistorySelect(query) }, label = { Text(query, maxLines = 1) })
                    }
                }
            }
            Box(Modifier.padding(12.dp)) {
                VehicleBrandField(search, "نام برند، مدل، تیپ یا شهر", onSearchChange)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Navy, fontWeight = FontWeight.Bold)
            TextButton(onClick = onRefresh, enabled = !loading) { Text("↻ تازه‌سازی") }
        }
        if (sortMode in listOf("ارزان‌ترین", "گران‌ترین") &&
            filteredCars.any { it.currency != priceCurrency }) {
            Text("برای مرتب‌سازی قیمت، ارز آگهی‌ها باید یکسان باشد. از فیلتر ارز و محدوده قیمت استفاده کن.",
                modifier = Modifier.padding(horizontal = 14.dp), fontSize = 12.sp, color = Color.Gray)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("جدیدترین", "ارزان‌ترین", "گران‌ترین", "کم‌کارکردترین", "جدیدترین مدل").forEach { option ->
                FilterChip(selected = sortMode == option, onClick = { sortMode = option }, label = { Text(option, fontSize = 12.sp) })
            }
        }
        TextButton(onClick = { filterOpen = !filterOpen }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(if (filterOpen) "بستن فیلترها" else "⚙ فیلترهای پیشرفته")
        }
        if (filterOpen) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VehicleBrandField(brandFilter, "برند، مدل یا تیپ") { brandFilter = it }
                OutlinedTextField(cityFilter, { cityFilter = it }, label = { Text("شهر یا کشور") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minYear, { minYear = it.filter(Char::isDigit).take(4) },
                        label = { Text("سال از") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxYear, { maxYear = it.filter(Char::isDigit).take(4) },
                        label = { Text("سال تا") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minMileage, { minMileage = it.filter(Char::isDigit) },
                        label = { Text("کارکرد از (km)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxMileage, { maxMileage = it.filter(Char::isDigit) },
                        label = { Text("کارکرد تا (km)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("ارز قیمت برای فیلتر", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("تومان" to "IRR", "دلار" to "USD", "یورو" to "EUR",
                        "درهم" to "AED", "ریال عمان" to "OMR", "لاری" to "GEL").forEach { (label, code) ->
                        FilterChip(selected = priceCurrency == code, onClick = { priceCurrency = code },
                            label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minPrice, { minPrice = it.filter(Char::isDigit) },
                        label = { Text(if (priceCurrency == "IRR") "قیمت از (تومان)" else "قیمت از ($priceCurrency)") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(maxPrice, { maxPrice = it.filter(Char::isDigit) },
                        label = { Text(if (priceCurrency == "IRR") "قیمت تا (تومان)" else "قیمت تا ($priceCurrency)") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("وضعیت خودرو", fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("همه" to "همه", "آماده تحویل" to "ready",
                        "خارج از کشور" to "abroad", "در مسیر" to "in_transit",
                        "گمرک" to "customs").forEach { (label, value) ->
                        FilterChip(selected = statusFilter == value,
                            onClick = { statusFilter = value }, label = { Text(label) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = negotiableOnly, onCheckedChange = { negotiableOnly = it })
                    Text("فقط قیمت توافقی")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = photosOnly, onCheckedChange = { photosOnly = it })
                    Text("فقط آگهی‌های عکس‌دار")
                }
                TextButton(onClick = {
                    brandFilter = ""; cityFilter = ""; minYear = ""; maxYear = ""
                    minMileage = ""; maxMileage = ""; minPrice = ""; maxPrice = ""
                    statusFilter = "همه"; priceCurrency = "IRR"; negotiableOnly = false; photosOnly = false
                }) { Text("پاک‌کردن فیلترها") }
                Text(if (serverFiltered) "فیلترها روی همهٔ آگهی‌های منتشرشده اعمال می‌شوند. مرتب‌سازی قیمت در ارز انتخاب‌شده انجام می‌شود." else "فیلترها روی آگهی‌های بارگذاری‌شده اعمال می‌شوند.",
                    color = Color.Gray, fontSize = 11.sp)
            }
        }
        if (!serverFiltered && hasMore && sortMode != "جدیدترین") Text("مرتب‌سازی روی آگهی‌های بارگذاری‌شده انجام می‌شود.", modifier = Modifier.padding(horizontal = 16.dp), color = Color.Gray, fontSize = 11.sp)
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (error != null) {
            EmptyPanel(error, "تلاش مجدد", onRefresh)
        } else if (!loading && sortedCars.isEmpty()) {
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
    val context = LocalContext.current
    var photoIndex by remember(car.id) { mutableIntStateOf(0) }
    val photoCount = car.imagePaths.size
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CarPhoto(car.imagePaths.getOrNull(photoIndex), car.title,
                    session?.takeIf { it.userId == car.userId }?.accessToken,
                    Modifier.size(110.dp))
                if (photoCount > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center) {
                        TextButton(
                            onClick = { photoIndex = (photoIndex - 1 + photoCount) % photoCount },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("‹", fontSize = 20.sp) }
                        Text("${photoIndex + 1}/$photoCount", color = Color.Gray, fontSize = 11.sp)
                        TextButton(
                            onClick = { photoIndex = (photoIndex + 1) % photoCount },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) { Text("›", fontSize = 20.sp) }
                    }
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(car.title, fontWeight = FontWeight.Bold, maxLines = 2, color = Ink)
                val details = listOf(car.make, car.model, car.trim).filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (details.isNotBlank()) Text(details, color = Ink, fontSize = 12.sp, maxLines = 2)
                Text("سال ${car.year} • ${car.country}، ${car.city}",
                    color = Color.Gray, fontSize = 12.sp, maxLines = 2)
                Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                car.mileageKm?.let {
                    Text("کارکرد " + java.text.NumberFormat.getNumberInstance().format(it) +
                        " کیلومتر", color = Color.Gray, fontSize = 12.sp)
                }
                val status = when (car.vehicleStatus) {
                    "abroad" -> "خارج از کشور"
                    "in_transit" -> "در مسیر"
                    "customs" -> "گمرک"
                    "ready" -> "آماده تحویل"
                    else -> ""
                }
                if (status.isNotBlank()) Text(status, color = Color.Gray, fontSize = 11.sp)
                if (car.listingStatus != "published")
                    Text("وضعیت: " + car.listingStatus, color = Gold, fontSize = 11.sp)
                if (car.listingStatus == "published") {
                    TextButton(onClick = {
                        val details = listOf(car.make, car.model, car.trim).filter { it.isNotBlank() }.joinToString(" ")
                        val message = listOf(car.title, details, "سال ساخت: ${car.year}",
                            car.mileageKm?.let { "کارکرد: $it کیلومتر" }.orEmpty(),
                            "قیمت: ${car.priceLabel()}", "موقعیت: ${car.city}، ${car.country}",
                            "شناسه آگهی: ${car.id}", "https://pelakauto.com/")
                            .filter { it.isNotBlank() }.joinToString("\n")
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message)
                            }, "اشتراک‌گذاری آگهی"))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "برنامه اشتراک‌گذاری پیدا نشد.", Toast.LENGTH_SHORT).show()
                        }
                    }, contentPadding = PaddingValues(0.dp)) { Text("↗ اشتراک‌گذاری", fontSize = 12.sp) }
                }
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
        Button(onClick = onGoToLogin) { Text("ورود یا ساخت حساب") }
    }
}

@Composable
private fun AccountPanel(
    api: PelakApi,
    session: PelakSession?,
    onLogin: (PelakSession) -> Unit,
    onLogout: () -> Unit,
    onNavigate: (Int) -> Unit,
    supportMessage: String? = null
) {
    if (session != null) {
        PelakAccountMenu(api, session, onNavigate, onLogout, supportMessage)
    } else {
        PelakAuthPanel(api, onLogin)
    }
}

@Composable
private fun DraftPanel(api: PelakApi, session: PelakSession, editing: Car?, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
    var make by rememberSaveable(editing?.id) { mutableStateOf(editing?.make.orEmpty()) }
    var model by rememberSaveable(editing?.id) { mutableStateOf(editing?.model.orEmpty()) }
    var trim by rememberSaveable(editing?.id) { mutableStateOf(editing?.trim.orEmpty()) }
    var mileage by rememberSaveable(editing?.id) { mutableStateOf(editing?.mileageKm?.toString().orEmpty()) }
    var vehicleStatus by rememberSaveable(editing?.id) { mutableStateOf(editing?.vehicleStatus ?: "ready") }
    var description by rememberSaveable(editing?.id) { mutableStateOf(editing?.description.orEmpty()) }
    var year by rememberSaveable(editing?.id) { mutableStateOf(editing?.year?.toString().orEmpty()) }
    var city by rememberSaveable(editing?.id) { mutableStateOf(editing?.city.orEmpty()) }
    var country by rememberSaveable(editing?.id) { mutableStateOf(editing?.country ?: "ایران") }
    var phone by rememberSaveable(editing?.id) {
        mutableStateOf(ListingPhone.nationalDisplay(editing?.sellerPhone.orEmpty(),
            ListingPhone.region(editing?.country ?: "ایران")))
    }
    val phoneRegion = remember(country) { ListingPhone.region(country) }
    val countryCode = remember(phoneRegion) { ListingPhone.callingCode(phoneRegion) }
    val normalizedPhone = remember(phone, phoneRegion) { ListingPhone.normalize(phone, phoneRegion) }
    var price by rememberSaveable(editing?.id) {
        mutableStateOf(
            if (editing?.currency == "IRR" && editing.price != null)
                runCatching { BigDecimal(editing.price).divide(BigDecimal.TEN).toPlainString() }.getOrDefault("")
            else ""
        )
    }
    var negotiable by rememberSaveable(editing?.id) { mutableStateOf(editing?.priceNegotiable ?: false) }
    var photos by remember(editing?.id) {
        mutableStateOf(editing?.imagePaths.orEmpty().map { DraftPhoto(path = it) })
    }
    var photoErrors by remember(editing?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var draftId by remember(editing?.id) { mutableStateOf(editing?.id) }
    var step by rememberSaveable(editing?.id) { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(if (editing == null) "ثبت آگهی خودرو" else "ویرایش آگهی",
                fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text("آگهی را ذخیره کن و از «آگهی‌های من» برای بررسی بفرست.",
                fontSize = 12.sp, color = Color.Gray)
        }
        item {
            Text("مرحله ${step + 1} از ۳", fontWeight = FontWeight.Bold, color = Navy)
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("مشخصات", "قیمت و موقعیت", "عکس و ثبت").forEachIndexed { index, label ->
                    Text(label, fontSize = 11.sp,
                        color = if (index == step) Navy else Color.Gray)
                }
            }
        }
        if (step == 0) {
        item { NativeField("عنوان آگهی", title) { title = it } }
        item { VehicleBrandField(make, "برند خودرو") { if (it != make) { model = ""; trim = "" }; make = it } }
        item { VehicleDetailField(model, make, null, "مدل خودرو") { if (it != model) trim = ""; model = it } }
        item { VehicleDetailField(trim, make, model, "تیپ خودرو") { trim = it } }
        item { NativeField("کارکرد (کیلومتر)", mileage, KeyboardType.Number) { mileage = it.filter(Char::isDigit) } }
        item { NativeField("سال ساخت میلادی", year, KeyboardType.Number) { year = it } }
        }
        if (step == 1) {
        item {
            NativeField("کشور", country) { country = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ListingPhone.preferredCountries.forEach { option ->
                    FilterChip(selected = country == option, onClick = { country = option }, label = { Text(option) })
                }
            }
        }
        item { NativeField("شهر", city) { city = it } }
        item {
            Text("وضعیت خودرو", color = Navy)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("آماده تحویل" to "ready", "خارج از کشور" to "abroad",
                    "در مسیر" to "in_transit", "گمرک" to "customs").forEach { (label, code) ->
                    FilterChip(selected = vehicleStatus == code, onClick = { vehicleStatus = code },
                        label = { Text(label) })
                }
            }
        }
        item {
            OutlinedTextField(phone, { phone = it.take(40) }, label = { Text("شماره تماس فروشنده") },
                placeholder = { if (phoneRegion == "IR") Text("۰۹۱۱۲۳۴۵۶۷۸") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                isError = phone.isNotBlank() && normalizedPhone == null,
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (phone.isNotBlank() && normalizedPhone == null) {
                Text(if (phoneRegion == "IR") "شماره را مثل ۰۹۱۱۲۳۴۵۶۷۸ وارد کن."
                    else if (countryCode != null) "شمارهٔ محلی معتبر وارد کن."
                    else "کشور را مشخص کن یا شمارهٔ بین‌المللی را با + وارد کن.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            if (countryCode != null) {
                Text("کد کشور آگهی: \u2066$countryCode\u2069 · برای شمارهٔ محلی خودکار اضافه می‌شود.",
                    fontSize = 12.sp, color = Color.Gray)
            }
            Text("شماره تماس در آگهی منتشرشده عمومی است.", fontSize = 12.sp, color = Color.Gray)
        }
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
        }
        if (step == 2) {
        item {
            OutlinedTextField(value = description, onValueChange = { description = it.take(4000) },
                label = { Text("توضیحات خودرو") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 6)
        }
        item {
            DraftPhotoEditor(photos, photoErrors, session.accessToken, !working) {
                photos = it
                photoErrors = photoErrors.filterKeys { id -> it.any { photo -> photo.id == id } }
            }
        }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }, enabled = !working) {
                    Text("مرحله قبل")
                }
                if (step < 2) Button(onClick = {
                    val y = year.toIntOrNull()
                    val p = price.replace(",", "").trim().toBigDecimalOrNull()
                    info = when {
                        step == 0 && (title.isBlank() || make.isBlank() || model.isBlank() ||
                            y == null || y !in 1950..2100 || (mileage.isNotBlank() && mileage.toIntOrNull() == null)) ->
                            "عنوان، برند، مدل و سال ساخت معتبر را وارد کن."
                        step == 1 && (country.isBlank() || city.isBlank() || normalizedPhone == null ||
                            (!negotiable && (p == null || p <= BigDecimal.ZERO))) ->
                            "کشور، شهر، شماره تماس معتبر و قیمت را وارد کن."
                        else -> ""
                    }
                    if (info.isBlank()) step++
                }, enabled = !working) { Text("مرحله بعد") }
            }
        }
        if (step == 2) item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !working,
                onClick = {
                    val y = year.toIntOrNull()
                    val p = price.replace(",", "").trim().toBigDecimalOrNull()
                    if (title.isBlank() || make.isBlank() || model.isBlank() ||
                        y == null || y !in 1950..2100 || country.isBlank() || city.isBlank() ||
                        (mileage.isNotBlank() && mileage.toIntOrNull() == null) || normalizedPhone == null || (!negotiable && (p == null || p <= BigDecimal.ZERO))) {
                        info = "عنوان، برند، مدل، سال، کارکرد، کشور، شهر، شماره تماس و قیمت را درست وارد کن."
                    } else {
                        scope.launch {
                            working = true; info = ""; uploadProgress = "در حال ذخیره اطلاعات آگهی…"
                            try {
                                val rials = if (negotiable) null else
                                    p!!.multiply(BigDecimal.TEN).toPlainString()
                                val savedId = draftId
                                val id = if (savedId == null) {
                                    api.createDraft(session, title, make, model, y, rials, country, city, normalizedPhone, trim, mileage.toIntOrNull(), description, vehicleStatus)
                                        .also { draftId = it }
                                } else {
                                    api.updateDraft(session, savedId, title, make, model, y, rials,
                                        country, city, normalizedPhone, trim, mileage.toIntOrNull(), description, vehicleStatus)
                                    savedId
                                }
                                photoErrors = emptyMap()
                                // Persist removals first, then attach each successful upload in the selected order.
                                api.attachPhotos(session, id, photos.mapNotNull { it.path })
                                val pending = photos.filter { it.path == null }
                                uploadPhotoBatch(pending,
                                    onProgress = { index, count -> uploadProgress = "در حال بارگذاری عکس $index از $count…" },
                                    upload = { photo ->
                                        val path = api.uploadPhoto(session, context.contentResolver,
                                            photo.uri ?: error("فایل عکس در دسترس نیست"), photo.id, photo.rotation)
                                        photos = photos.map { if (it.id == photo.id) it.copy(path = path) else it }
                                        api.attachPhotos(session, id, photos.mapNotNull { it.path })
                                    },
                                    onFailure = { photo -> photoErrors = photoErrors + (photo.id to "نیازمند تلاش دوباره") }
                                )
                                // One final reconciliation covers an attachment whose first response was lost.
                                api.attachPhotos(session, id, photos.mapNotNull { it.path })
                                val missing = photos.count { it.path == null }
                                if (missing == 0) onSaved()
                                else info = "پیش‌نویس ذخیره شد؛ $missing عکس باقی مانده است. دوباره ذخیره را بزن یا آن عکس را جایگزین کن."

                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                val pendingPhotos = photos.count { it.path == null }
                                info = if (draftId == null) {
                                    "ثبت پیش‌نویس انجام نشد؛ اتصال اینترنت و اطلاعات آگهی را بررسی کن و دوباره تلاش کن."
                                } else if (pendingPhotos > 0) {
                                    "پیش‌نویس با شناسه ${draftId} ذخیره شده، اما ${pendingPhotos} عکس هنوز متصل نشده است. دوباره ذخیره را بزن؛ عکس‌های قبلی تکراری بارگذاری نمی‌شوند."
                                } else {
                                    "ذخیره آگهی کامل نشد؛ پیش‌نویس موجود است. دوباره تلاش کن."
                                }
                            } finally { working = false; uploadProgress = "" }
                        }
                    }
                }
            ) { Text(if (working) "در حال ذخیره و آپلود…" else "ذخیره پیش‌نویس و عکس‌ها") }
            if (working && uploadProgress.isNotBlank()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(uploadProgress, fontSize = 12.sp, color = Navy)
            }
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
internal fun CarPhoto(
    path: String?, title: String, jwt: String?, modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop
) {
    val api = remember { PelakApi() }
    val context = LocalContext.current
    var retry by remember(path, jwt) { mutableIntStateOf(0) }
    var failed by remember(path, jwt, retry) { mutableStateOf(false) }
    var loaded by remember(path, jwt, retry) { mutableStateOf(false) }
    val url by produceState<String?>(null, path, jwt, retry) {
        value = null
        failed = false
        loaded = false
        value = path?.let { api.signedPhoto(it, jwt) }
        if (path != null && value == null) failed = true
    }
    val request = remember(url, path, jwt) {
        coil.request.ImageRequest.Builder(context).data(url)
            .memoryCacheKey(path?.let { photoCacheKey(it, jwt) })
            .diskCacheKey(path?.let { photoCacheKey(it, jwt) }).crossfade(false).build()
    }
    Box(modifier.background(Pale, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(request, title, Modifier.fillMaxSize(),
            contentScale = contentScale, onSuccess = { loaded = true; failed = false },
            onError = { failed = true })
        if (path == null) Text("🚘", fontSize = 27.sp)
        else if (failed) TextButton(onClick = { retry++ }) { Text("دریافت دوبارهٔ عکس") }
        else if (!loaded) CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
private fun ChatInbox(api: PelakApi, session: PelakSession, onThread: (ChatTarget) -> Unit) {
    var messages by remember(session.userId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var inboxSearch by rememberSaveable(session.userId) { mutableStateOf("") }
    var busy by remember(session.userId) { mutableStateOf(true) }
    var error by remember(session.userId) { mutableStateOf<String?>(null) }
    var reload by remember(session.userId) { mutableIntStateOf(0) }
    // Refresh inbox while this screen is visible; cancelled when the user navigates away.
    LaunchedEffect(session.userId) {
        while (true) {
            delay(20000)
            reload++
        }
    }
    LaunchedEffect(session.userId, session.accessToken, reload) {
        if (messages.isEmpty()) busy = true
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
        OutlinedTextField(
            value = inboxSearch,
            onValueChange = { inboxSearch = it.take(100) },
            label = { Text("جست‌وجو در پیام‌ها یا شناسه آگهی") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )
        // The API returns newest messages first. Group once to avoid scanning all messages
        // for every conversation during each recomposition.
        val conversations = messages.groupBy { msg ->
            msg.listingId + ":" + if (msg.senderId == session.userId) msg.recipientId else msg.senderId
        }
        val threads = conversations.values.mapNotNull { it.firstOrNull() }
        val term = inboxSearch.trim()
        val visibleThreads = if (term.isEmpty()) threads else threads.filter { msg ->
            val peer = if (msg.senderId == session.userId) msg.recipientId else msg.senderId
            val key = msg.listingId + ":" + peer
            msg.listingId.contains(term, ignoreCase = true) ||
                conversations[key].orEmpty().any { it.body.contains(term, ignoreCase = true) }
        }
        if (!busy && threads.isEmpty()) Text("هنوز گفت‌وگویی نداری.", color = Color.Gray)
        else if (!busy && visibleThreads.isEmpty()) Text("گفت‌وگوی مطابق جست‌وجو پیدا نشد.", color = Color.Gray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(visibleThreads, key = { it.id }) { msg ->
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
    val messageScroll = rememberScrollState()
    LaunchedEffect(messages.lastOrNull()?.id) {
        messageScroll.animateScrollTo(messageScroll.maxValue)
    }
    // Lightweight polling until native realtime subscriptions are available.
    LaunchedEffect(target.listingId, target.peerId) {
        while (true) {
            delay(12000)
            reload++
        }
    }
    LaunchedEffect(target.listingId, target.peerId, session.accessToken, reload) {
        try { messages = api.thread(session, target.listingId, target.peerId); error = null }
        catch (_: Exception) { error = "خواندن پیام‌ها ممکن نشد." }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(target.title, maxLines = 1, color = Navy) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(max = 310.dp).verticalScroll(messageScroll),
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


