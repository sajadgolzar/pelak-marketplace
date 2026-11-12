package com.pelakauto.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import java.math.BigDecimal

private val Navy = Color(0xFF10233F)
private val Gold = Color(0xFFC9A158)
private val Pale = Color(0xFFF6F8FB)
private val Ink = Color(0xFF172940)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 35, 63)
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

private val nav = listOf(
    "آگهی‌ها" to "🚘",
    "جست‌وجو" to "⌕",
    "ثبت آگهی" to "＋",
    "آگهی‌های من" to "▣",
    "حساب من" to "♙"
)

@Composable
private fun PelakNativeApp() {
    val api = remember { PelakApi() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var mine by remember { mutableStateOf<List<Car>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var mineLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Car?>(null) }
    var session by remember { mutableStateOf<PelakSession?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try { cars = api.published() }
            catch (_: Exception) { error = "دریافت آگهی‌ها ممکن نشد. اینترنت را بررسی کن." }
            finally { loading = false }
        }
    }
    fun refreshMine(current: PelakSession) {
        scope.launch {
            mineLoading = true
            try { mine = api.mine(current) }
            catch (_: Exception) { mine = emptyList() }
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
                        onClick = { tab = index },
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
                    val filtered = remember(cars, search, tab) {
                        if (tab == 0 || search.isBlank()) cars
                        else cars.filter { row ->
                            listOf(row.title, row.make, row.model, row.trim, row.city)
                                .any { it.contains(search.trim(), ignoreCase = true) }
                        }
                    }
                    ListingsPanel(
                        cars = filtered,
                        loading = loading,
                        error = error,
                        search = search,
                        searching = tab == 1,
                        onSearchChange = { search = it },
                        onRefresh = { refresh() },
                        onSelect = { selected = it },
                        onPost = { tab = 2 }
                    )
                }
                2 -> {
                    val current = session
                    if (current == null) LoginRequired(onGoToLogin = { tab = 4 })
                    else DraftPanel(api = api, session = current, onSaved = {
                        tab = 3
                        refreshMine(current)
                    })
                }
                3 -> {
                    if (session == null) LoginRequired(onGoToLogin = { tab = 4 })
                    else ListingsPanel(
                        cars = mine, loading = mineLoading, error = null,
                        search = "", searching = false, onSearchChange = {},
                        onRefresh = { session?.let(::refreshMine) },
                        onSelect = { selected = it }, onPost = { tab = 2 },
                        title = "آگهی‌های من"
                    )
                }
                else -> AccountPanel(
                    api = api,
                    session = session,
                    onLogin = { session = it; tab = 3 },
                    onLogout = { session = null; mine = emptyList(); tab = 0 }
                )
            }
        }
    }

    selected?.let { car ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(car.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(car.make + "  " + car.model + "  " + car.trim)
                    Text("سال ساخت: " + car.year + "  •  " + car.country + "، " + car.city)
                    car.mileageKm?.let { Text("کارکرد: " + it + " کیلومتر") }
                    Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold)
                    if (car.listingStatus != "published") Text("وضعیت: " + car.listingStatus)
                    if (car.description.isNotBlank()) Text(car.description)
                }
            },
            confirmButton = {
                if (car.listingStatus == "published" && car.sellerPhone.isNotBlank()) {
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_DIAL,
                            Uri.parse("tel:" + car.sellerPhone.filter { it.isDigit() || it == '+' })))
                    }) { Text("تماس با فروشنده") }
                } else {
                    TextButton(onClick = { selected = null }) { Text("بستن") }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("بازگشت") }
            }
        )
    }
}

@Composable
private fun ListingsPanel(
    cars: List<Car>,
    loading: Boolean,
    error: String?,
    search: String,
    searching: Boolean,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (Car) -> Unit,
    onPost: () -> Unit,
    title: String = "جدیدترین آگهی‌های خودرو"
) {
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
                items(cars, key = { it.id }) { car ->
                    CarRow(car = car, onClick = { onSelect(car) })
                }
            }
        }
    }
}

@Composable
private fun CarRow(car: Car, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val api = remember { PelakApi() }
            val imageUrl by produceState<String?>(initialValue = null, key1 = car.id) {
                value = car.imagePath?.let { api.signedPhoto(it) }
            }
            Box(
                modifier = Modifier.size(110.dp).background(Pale, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl == null) Text("🚘", fontSize = 30.sp)
                else AsyncImage(
                    model = imageUrl, contentDescription = car.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(car.title, fontWeight = FontWeight.Bold, maxLines = 2, color = Ink)
                Text(
                    "مدل " + car.year + " • " + car.country + " " + car.city,
                    color = Color.Gray, fontSize = 12.sp, maxLines = 1
                )
                Text(car.priceLabel(), color = Navy, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("به پلاک خوش آمدی", fontWeight = FontWeight.Bold, color = Navy, fontSize = 21.sp)
            Spacer(Modifier.height(10.dp))
            Text(session.email)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onLogout) { Text("خروج از حساب در این دستگاه") }
            Spacer(Modifier.height(10.dp))
            Text("در این نسخه آزمایشی، نشست ورود فقط تا بسته‌شدن برنامه حفظ می‌شود.",
                color = Color.Gray, fontSize = 12.sp)
        }
        return
    }
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var otp by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ورود / ثبت‌نام پلاک", fontWeight = FontWeight.Bold, color = Navy, fontSize = 21.sp)
        Text("با ایمیل خود کد ورود یک‌بارمصرف دریافت کن.", color = Color.Gray)
        OutlinedTextField(value = email, onValueChange = { email = it },
            label = { Text("ایمیل") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth())
        if (sent) {
            OutlinedTextField(value = otp, onValueChange = { otp = it },
                label = { Text("کد ۶ رقمی دریافتی") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
        }
        Button(
            enabled = !busy && email.contains("@") && (!sent || otp.length >= 6),
            onClick = {
                scope.launch {
                    busy = true; info = ""
                    try {
                        if (!sent) {
                            api.sendOtp(email)
                            sent = true
                            info = "کد به ایمیلت ارسال شد."
                        } else {
                            onLogin(api.verifyOtp(email, otp))
                            otp = ""
                        }
                    } catch (_: Exception) {
                        info = "عملیات ورود ناموفق بود؛ ایمیل، کد یا اتصال را بررسی کن."
                    } finally { busy = false }
                }
            }
        ) { Text(if (busy) "در حال بررسی…" else if (sent) "تأیید و ورود" else "دریافت کد") }
        if (sent) TextButton(onClick = { sent = false; otp = "" }) { Text("ارسال مجدد کد") }
        if (info.isNotBlank()) Text(info, color = Navy)
    }
}

@Composable
private fun DraftPanel(api: PelakApi, session: PelakSession, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var make by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var country by rememberSaveable { mutableStateOf("ایران") }
    var phone by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var negotiable by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("ثبت پیش‌نویس آگهی", fontSize = 21.sp,
                fontWeight = FontWeight.Bold, color = Navy)
            Text("این فرم مستقیماً در Supabase ذخیره می‌شود، نه در وب‌سایت.",
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
                                api.createDraft(
                                    session, title, make, model, y,
                                    if (negotiable) null else p!!.multiply(BigDecimal.TEN).toPlainString(),
                                    country, city, phone
                                )
                                info = "پیش‌نویس با موفقیت ذخیره شد."
                                onSaved()
                            } catch (_: Exception) {
                                info = "ثبت پیش‌نویس انجام نشد؛ مجوز، محدودیت آگهی و اینترنت را بررسی کن."
                            } finally { working = false }
                        }
                    }
                }
            ) { Text(if (working) "در حال ذخیره…" else "ذخیره پیش‌نویس") }
        }
        item { if (info.isNotBlank()) Text(info, color = Navy) }
        item {
            Text("انتشار عمومی و بررسی تصاویر هنوز در این نسخه بومی فعال نیست و باید از مسیر مدیریت انجام شود.",
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
