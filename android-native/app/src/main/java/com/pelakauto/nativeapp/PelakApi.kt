package com.pelakauto.nativeapp

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal data class Car(
    val id: String,
    val userId: String,
    val title: String,
    val make: String,
    val model: String,
    val trim: String,
    val year: Int,
    val mileageKm: Int?,
    val price: String?,
    val priceNegotiable: Boolean,
    val currency: String,
    val country: String,
    val city: String,
    val description: String,
    val vehicleStatus: String,
    val listingStatus: String,
    val imagePaths: List<String>,
    val sellerPhone: String
) {
    val imagePath: String? get() = imagePaths.firstOrNull()
    fun priceLabel(): String {
        if (priceNegotiable || price == null) return "قیمت توافقی"
        return try {
            val amount = BigDecimal(price)
            val isIranianRial = currency == "IRR"
            val rendered = if (isIranianRial) amount.divide(BigDecimal.TEN) else amount
            NumberFormat.getNumberInstance(Locale("fa", "IR")).format(rendered) +
                if (isIranianRial) " تومان" else " " + currency
        } catch (_: Exception) { "قیمت نامشخص" }
    }
}
internal data class PelakSession(
    val accessToken: String,
    val userId: String,
    val email: String,
    val refreshToken: String,
    val expiresAtMs: Long
)
internal data class ChatMessage(
    val id: String, val listingId: String, val senderId: String,
    val recipientId: String, val body: String, val createdAt: String
)

internal fun matchesUploadedPhoto(body: String, filename: String, bytes: Int): Boolean = runCatching {
    val rows = JSONArray(body)
    (0 until rows.length()).any { i ->
        val item = rows.optJSONObject(i)
        item != null && item.optString("name") == filename &&
            item.optJSONObject("metadata")?.optLong("size", -1L) == bytes.toLong()
    }
}.getOrDefault(false)

internal class PelakApi {
    private val preferredPhotoRelay = java.util.concurrent.atomic.AtomicBoolean(true)
    private val photoUploadClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(18, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val photoProbeClient = photoUploadClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun perform(
        route: String,
        method: String = "GET",
        data: JSONObject? = null,
        jwt: String? = null,
        prefer: String = "return=representation"
    ): String {
        val connection = URL(PelakConfig.API_ROOT + route).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("apikey", PelakConfig.PUBLIC_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (jwt != null) connection.setRequestProperty("Authorization", "Bearer " + jwt)
            if (data != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Prefer", prefer)
                connection.outputStream.use { it.write(data.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = source?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("خطای سرور (" + code + ")")
            return body
        } finally { connection.disconnect() }
    }

    private val carFields = "id,user_id,title,make,model,trim,year,mileage_km,price,price_negotiable,currency,country,city,description,vehicle_status,listing_status,image_paths,seller_phone"
    private fun parseCars(response: String): List<Car> {
        val list = JSONArray(response)
        return (0 until list.length()).map { index ->
            val j = list.getJSONObject(index)
            val paths = j.optJSONArray("image_paths")
            Car(
                id = j.optString("id"),
                userId = j.optString("user_id"),
                title = j.optString("title"),
                make = j.optString("make"),
                model = j.optString("model"),
                trim = j.optString("trim"),
                year = j.optInt("year"),
                mileageKm = if (j.isNull("mileage_km")) null else j.optInt("mileage_km"),
                price = if (j.isNull("price")) null else j.opt("price")?.toString(),
                priceNegotiable = j.optBoolean("price_negotiable"),
                currency = j.optString("currency", "IRR"),
                country = j.optString("country"),
                city = j.optString("city"),
                description = j.optString("description"),
                vehicleStatus = j.optString("vehicle_status"),
                listingStatus = j.optString("listing_status"),
                imagePaths = if (paths == null) emptyList() else (0 until paths.length())
                    .mapNotNull { paths.optString(it).takeIf(String::isNotBlank) },
                sellerPhone = j.optString("seller_phone")
            )
        }
    }
    // Server-side search sees the entire published catalogue, not only the first page.
    suspend fun published(query: String = "", offset: Int = 0, pageSize: Int = 100,
        filters: ListingFilters = ListingFilters()): List<Car> =
        withContext(Dispatchers.IO) {
            val clean = query.replace(Regex("[^\\p{L}\\p{N} \\-]"), "").trim().take(60)
            val condition = if (clean.isEmpty()) "" else {
                val word = Uri.encode(clean)
                "&or=(title.ilike.*" + word + "*,make.ilike.*" + word +
                    "*,model.ilike.*" + word + "*,trim.ilike.*" + word +
                    "*,city.ilike.*" + word + "*)"
            }
            parseCars(perform("/rest/v1/listings?select=" + carFields +
                "&listing_status=eq.published" + condition +
                filters.parameters() + "&limit=" + pageSize.coerceIn(1,100) +
                "&offset=" + offset.coerceAtLeast(0)))
        }
    suspend fun mine(session: PelakSession, offset: Int = 0, pageSize: Int = 100): List<Car> = withContext(Dispatchers.IO) {
        parseCars(perform("/rest/v1/listings?select=" + carFields +
            "&user_id=eq." + session.userId + "&order=created_at.desc" +
            "&limit=" + pageSize.coerceIn(1, 100) + "&offset=" + offset.coerceAtLeast(0),
            jwt = session.accessToken))
    }
    // The Storage API returns /object/sign/... on some versions and /storage/v1/object/sign/... on others.
    companion object {
        private data class SignedPhoto(val url: String, val expires: Long)
        private val photoUrls = java.util.concurrent.ConcurrentHashMap<String, SignedPhoto>()
        private val photoLocks = Array(16) { kotlinx.coroutines.sync.Mutex() }
    }

    suspend fun signedPhoto(path: String, jwt: String? = null): String? = withContext(Dispatchers.IO) {
        val key = photoCacheKey(path, jwt)
        val lock = photoLocks[(key.hashCode() and Int.MAX_VALUE) % photoLocks.size]
        lock.lock()
        try {
            photoUrls[key]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return@withContext it.url }
            val response = perform("/storage/v1/object/sign/car-images/" + Uri.encode(path, "/"),
                "POST", JSONObject().put("expiresIn", 900), jwt)
            val signed = JSONObject(response).optString("signedURL")
            val url = when {
                signed.startsWith("/storage/v1/") -> PelakConfig.API_ROOT + signed
                signed.startsWith("/object/sign/") -> PelakConfig.API_ROOT + "/storage/v1" + signed
                signed.startsWith("https://" + Uri.parse(PelakConfig.API_ROOT).host + "/storage/v1/") -> signed
                else -> return@withContext null
            }
            if (photoUrls.size > 500) photoUrls.clear()
            photoUrls[key] = SignedPhoto(url, System.currentTimeMillis() + 12 * 60 * 1000)
            url
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } finally { lock.unlock() }
    }

    suspend fun publishedByIds(ids: List<String>): List<Car> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val valid = ids.filter { it.matches(Regex("[0-9a-fA-F-]{36}")) }.distinct().take(50)
        if (valid.isEmpty()) return@withContext emptyList()
        val rows = parseCars(perform("/rest/v1/listings?select=" + carFields +
            "&listing_status=eq.published&id=in.(" + valid.joinToString(",") + ")"))
        val order = valid.withIndex().associate { it.value to it.index }
        rows.sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    // Favorites are stored in the existing RLS-protected pelak_favorites table.
    suspend fun favoriteIds(session: PelakSession): Set<String> = withContext(Dispatchers.IO) {
        val rows = JSONArray(perform("/rest/v1/pelak_favorites?select=listing_id&user_id=eq." +
            Uri.encode(session.userId) + "&limit=1000", jwt = session.accessToken))
        (0 until rows.length()).map { rows.getJSONObject(it).getString("listing_id") }.toSet()
    }

    suspend fun setFavorite(session: PelakSession, listingId: String, saved: Boolean) =
        withContext(Dispatchers.IO) {
            if (saved) {
                perform("/rest/v1/pelak_favorites", "POST", JSONObject()
                    .put("user_id", session.userId).put("listing_id", listingId), session.accessToken)
            } else {
                perform("/rest/v1/pelak_favorites?user_id=eq." + Uri.encode(session.userId) +
                    "&listing_id=eq." + Uri.encode(listingId), "DELETE", jwt = session.accessToken)
            }
            Unit
        }

    suspend fun favoriteCars(session: PelakSession): List<Car> = withContext(Dispatchers.IO) {
        val ids = favoriteIds(session)
        if (ids.isEmpty()) return@withContext emptyList()
        val all = mutableListOf<Car>()
        ids.chunked(50).forEach { chunk ->
            val filter = chunk.joinToString(",")
            all += parseCars(perform("/rest/v1/listings?select=" + carFields +
                "&listing_status=eq.published&id=in.(" + filter + ")", jwt = session.accessToken))
        }
        all
    }

    suspend fun signInWithPassword(email: String, password: String): PelakSession = withContext(Dispatchers.IO) {
        val raw = JSONObject(perform("/auth/v1/token?grant_type=password", "POST",
            JSONObject().put("email", email.trim()).put("password", password)))
        newSession(raw, email.trim())
    }
    suspend fun createAccount(email: String, password: String): PelakSession? = withContext(Dispatchers.IO) {
        val response = JSONObject(perform("/auth/v1/signup", "POST",
            JSONObject().put("email", email).put("password", password)))
        if (response.optString("access_token").isNotBlank()) newSession(response, email) else null
    }

    suspend fun requestPasswordReset(email: String): Unit = withContext(Dispatchers.IO) {
        perform("/auth/v1/recover", "POST", JSONObject().put("email", email))
    }

    suspend fun resendSignupCode(email: String): Unit = withContext(Dispatchers.IO) {
        perform("/auth/v1/resend", "POST", JSONObject().put("email", email).put("type", "signup"))
    }

    suspend fun verifyAccountCode(email: String, code: String, recovery: Boolean): PelakSession =
        withContext(Dispatchers.IO) {
            val type = if (recovery) "recovery" else "email"
            val response = JSONObject(perform("/auth/v1/verify", "POST",
                JSONObject().put("email", email).put("token", code).put("type", type)))
            newSession(response, email)
        }

    suspend fun updatePassword(session: PelakSession, password: String): Unit = withContext(Dispatchers.IO) {
        perform("/auth/v1/user", "PUT", JSONObject().put("password", password), session.accessToken)
    }

    suspend fun sendOtp(email: String) = withContext(Dispatchers.IO) {
        perform("/auth/v1/otp", "POST", JSONObject().put("email", email.trim()).put("create_user", true))
        Unit
    }
    private fun newSession(raw: JSONObject, fallbackEmail: String): PelakSession {
        val access = raw.optString("access_token")
        val refresh = raw.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("نشست نامعتبر است")
        val user = raw.optJSONObject("user")
        val id = user?.optString("id").orEmpty()
        if (id.isBlank()) throw IllegalStateException("شناسه کاربر در پاسخ وجود ندارد")
        return PelakSession(access, id, user?.optString("email").orEmpty().ifBlank { fallbackEmail },
            refresh, System.currentTimeMillis() + raw.optLong("expires_in", 3600L) * 1000L)
    }
    suspend fun verifyOtp(email: String, token: String): PelakSession = withContext(Dispatchers.IO) {
        val raw = JSONObject(perform("/auth/v1/verify", "POST", JSONObject()
            .put("type", "email").put("email", email.trim()).put("token", token.trim())))
        newSession(raw, email.trim())
    }
    suspend fun refreshSession(session: PelakSession): PelakSession = withContext(Dispatchers.IO) {
        val raw = JSONObject(perform("/auth/v1/token?grant_type=refresh_token", "POST",
            JSONObject().put("refresh_token", session.refreshToken)))
        newSession(raw, session.email)
    }

    private fun listingPayload(
        title: String, make: String, model: String, year: Int, priceRials: String?,
        country: String, city: String, phone: String, trim: String = "", mileageKm: Int? = null,
        description: String = "", vehicleStatus: String = "ready"
    ): JSONObject = JSONObject()
        .put("title", title.trim()).put("make", make.trim()).put("model", model.trim())
        .put("year", year).put("currency", "IRR")
        .put("trim", trim.trim()).put("mileage_km", mileageKm ?: JSONObject.NULL)
        .put("description", description.trim())
        .put("country", country.trim()).put("city", city.trim())
        .put("vehicle_status", vehicleStatus).put("seller_phone", phone.trim())
        .put("seller_contact_consent", true)
        .put("price_negotiable", priceRials == null)
        .put("price", if (priceRials == null) JSONObject.NULL else BigDecimal(priceRials))
    suspend fun createDraft(
        session: PelakSession, title: String, make: String, model: String,
        year: Int, priceRials: String?, country: String, city: String, phone: String,
        trim: String = "", mileageKm: Int? = null, description: String = "",
        vehicleStatus: String = "ready"
    ): String = withContext(Dispatchers.IO) {
        val payload = listingPayload(title, make, model, year, priceRials, country, city, phone,
            trim, mileageKm, description, vehicleStatus)
            .put("user_id", session.userId).put("listing_status", "draft")
        val row = JSONArray(perform("/rest/v1/listings", "POST", payload, session.accessToken))
        if (row.length() == 0) throw IllegalStateException("پیش‌نویس ذخیره نشد")
        row.getJSONObject(0).getString("id")
    }
    suspend fun updateDraft(
        session: PelakSession, id: String, title: String, make: String,
        model: String, year: Int, priceRials: String?, country: String, city: String, phone: String,
        trim: String = "", mileageKm: Int? = null, description: String = "",
        vehicleStatus: String = "ready"
    ) = withContext(Dispatchers.IO) {
        val payload = listingPayload(title, make, model, year, priceRials, country, city, phone,
            trim, mileageKm, description, vehicleStatus)
        val result = JSONArray(perform("/rest/v1/listings?id=eq." + Uri.encode(id) +
            "&user_id=eq." + session.userId + "&listing_status=eq.draft",
            "PATCH", payload, session.accessToken))
        if (result.length() != 1) throw IllegalStateException("فقط پیش‌نویس خودت قابل ویرایش است")
        Unit
    }
    suspend fun attachPhotos(session: PelakSession, id: String, paths: List<String>) =
        withContext(Dispatchers.IO) {
            require(paths.size <= 8 && paths.distinct().size == paths.size) {
                "حداکثر ۸ عکس غیرتکراری برای هر آگهی مجاز است"
            }
            val arr = JSONArray()
            paths.forEach { arr.put(it) }
            try {
                val result = JSONArray(perform("/rest/v1/listings?id=eq." + Uri.encode(id) +
                    "&user_id=eq." + session.userId + "&listing_status=eq.draft",
                    "PATCH", JSONObject().put("image_paths", arr), session.accessToken))
                check(result.length() == 1) { "اتصال عکس‌ها به آگهی انجام نشد" }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val actual = JSONArray(perform("/rest/v1/listings?select=image_paths&id=eq." + Uri.encode(id) +
                    "&user_id=eq." + session.userId, jwt = session.accessToken))
                val stored = actual.optJSONObject(0)?.optJSONArray("image_paths")
                val matches = stored != null && stored.length() == paths.size &&
                    paths.indices.all { stored.getString(it) == paths[it] }
                if (!matches) throw failure
            }
            Unit
        }
    suspend fun uploadPhoto(session: PelakSession, resolver: ContentResolver, image: Uri,
        photoId: String = UUID.randomUUID().toString(), rotation: Int = 0): String =
        withContext(Dispatchers.IO) {
            require(photoId.matches(Regex("[a-fA-F0-9-]{36}")))
            val bytes = preparePhotoBounded(resolver, image, rotation)
            val path = session.userId + "/" + photoId + ".jpg"
            var lastError: Exception? = null
            // Retry with the same UUID over an independent HTTPS route on PELAK's own domain.
            val firstRelay = preferredPhotoRelay.get()
            for (attempt in 0..1) {
                val relay = if (attempt == 0) firstRelay else !firstRelay
                val endpoint = if (relay) photoRelayUrl(session.userId, photoId)
                    else PelakConfig.API_ROOT + "/storage/v1/object/car-images/" + Uri.encode(path, "/")
                val builder = okhttp3.Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("x-upsert", "false")
                    .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                if (!relay) builder.header("apikey", PelakConfig.PUBLIC_KEY)
                try {
                    photoUploadClient.newCall(builder.build()).execute().use { response ->
                        val status = response.code
                        if (response.isSuccessful) {
                            preferredPhotoRelay.set(relay)
                            return@withContext path
                        }
                        if (!relay && status in listOf(401, 403, 413))
                            throw IllegalArgumentException("پاسخ سرور: HTTP $status")
                        throw java.io.IOException("پاسخ سرور: HTTP $status")
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: IllegalArgumentException) {
                    throw failure
                } catch (failure: Exception) {
                    lastError = failure
                    // A lost response must not trigger a duplicate or discard a committed photo.
                    if (photoExists(session, path, bytes.size, relay)) {
                        preferredPhotoRelay.set(relay)
                        return@withContext path
                    }
                }
                if (attempt == 0) kotlinx.coroutines.delay(250L)
            }
            throw lastError ?: IllegalStateException("آپلود عکس انجام نشد")
        }

    private fun photoRelayUrl(owner: String, photo: String): String =
        "https://pelakauto.ir/api/native-photo-upload?owner=" + Uri.encode(owner) + "&photo=" + Uri.encode(photo)

    private fun photoExists(session: PelakSession, path: String, expectedBytes: Int, relayFirst: Boolean): Boolean {
        val filename = path.substringAfterLast('/')
        val photoId = filename.removeSuffix(".jpg")
        for (relay in listOf(relayFirst, !relayFirst)) {
            try {
                val builder = okhttp3.Request.Builder()
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("Cache-Control", "no-cache")
                if (relay) {
                    builder.url(photoRelayUrl(session.userId, photoId) + "&size=" + expectedBytes).get()
                } else {
                    val payload = JSONObject().put("prefix", session.userId)
                        .put("search", filename).put("limit", 20)
                    builder.url(PelakConfig.API_ROOT + "/storage/v1/object/list/car-images")
                        .header("apikey", PelakConfig.PUBLIC_KEY)
                        .post(payload.toString().toRequestBody("application/json".toMediaType()))
                }
                photoProbeClient.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val found = if (relay) JSONObject(body).optBoolean("exists", false)
                            else matchesUploadedPhoto(body, filename, expectedBytes)
                        if (found) return true
                    }
                }
            } catch (_: Exception) { /* The other trusted route may still respond. */ }
        }
        return false
    }

    suspend fun removeUnattachedPhoto(session: PelakSession, path: String) =
        withContext(Dispatchers.IO) {
            val conn = URL(PelakConfig.API_ROOT + "/storage/v1/object/car-images/" +
                Uri.encode(path, "/")).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "DELETE"
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                conn.setRequestProperty("apikey", PelakConfig.PUBLIC_KEY)
                conn.setRequestProperty("Authorization", "Bearer " + session.accessToken)
                if (conn.responseCode !in 200..299) throw IllegalStateException("پاک‌سازی عکس ناتمام ماند")
            } finally { conn.disconnect() }
            Unit
        }

    suspend fun inbox(session: PelakSession): List<ChatMessage> = withContext(Dispatchers.IO) {
        val resp = perform("/rest/v1/pelak_listing_messages?select=id,listing_id,sender_id,recipient_id,body,created_at" +
            "&or=(sender_id.eq." + session.userId + ",recipient_id.eq." + session.userId +
            ")&order=created_at.desc&limit=100", jwt = session.accessToken)
        parseMessages(resp)
    }
    suspend fun thread(session: PelakSession, listingId: String, peerId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            parseMessages(perform("/rest/v1/pelak_listing_messages?select=id,listing_id,sender_id,recipient_id,body,created_at" +
                "&listing_id=eq." + Uri.encode(listingId) +
                "&order=created_at.asc&limit=200", jwt = session.accessToken))
                .filter { (it.senderId == session.userId && it.recipientId == peerId) ||
                    (it.senderId == peerId && it.recipientId == session.userId) }
        }
    private fun parseMessages(response: String): List<ChatMessage> {
        val array = JSONArray(response)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ChatMessage(item.optString("id"), item.optString("listing_id"),
                item.optString("sender_id"), item.optString("recipient_id"),
                item.optString("body"), item.optString("created_at"))
        }
    }
    suspend fun sendMessage(session: PelakSession, listingId: String, recipientId: String, body: String) =
        withContext(Dispatchers.IO) {
            perform("/rest/v1/rpc/pelak_send_listing_message", "POST",
                JSONObject().put("p_listing_id", listingId)
                    .put("p_recipient_id", recipientId).put("p_body", body.trim()), session.accessToken)
            Unit
        }
    suspend fun reportListing(session: PelakSession, listingId: String, category: String, details: String) =
        withContext(Dispatchers.IO) {
            require(category in setOf("fraud", "incorrect", "wrong_photos", "duplicate", "suspicious_contact", "other"))
            require(details.trim().length in 10..1200)
            perform("/rest/v1/rpc/pelak_report_listing", "POST",
                JSONObject().put("p_listing_id", listingId)
                    .put("p_category", category)
                    .put("p_details", details.trim()), session.accessToken)
            Unit
        }
    data class RevisionNotice(val reason: String, val deleteAfter: String?)

    suspend fun myRevisionReasons(session: PelakSession): Map<String, RevisionNotice> =
        withContext(Dispatchers.IO) {
            val rows = JSONArray(perform(
                "/rest/v1/pelak_listing_rejections?select=listing_id,reason,review_state,delete_after" +
                    "&owner_id=eq." + Uri.encode(session.userId) +
                    "&review_state=eq.needs_revision",
                jwt = session.accessToken
            ))
            (0 until rows.length()).associate { index ->
                val item = rows.getJSONObject(index)
                item.getString("listing_id") to RevisionNotice(
                    item.optString("reason", "نیازمند اصلاح"),
                    item.optString("delete_after").takeUnless { it.isBlank() || it == "null" }
                )
            }
        }

    suspend fun cancelMyListing(session: PelakSession, listingId: String) =
        withContext(Dispatchers.IO) {
            val result = perform("/rest/v1/rpc/pelak_cancel_my_listing", "POST",
                JSONObject().put("p_listing_id", listingId), session.accessToken)
            if (result.trim() != "true") throw IllegalStateException("لغو آگهی تأیید نشد")
            Unit
        }

    suspend fun renewMyListing(session: PelakSession, listingId: String) =
        withContext(Dispatchers.IO) {
            val result = perform("/rest/v1/rpc/pelak_renew_my_listing", "POST",
                JSONObject().put("p_listing_id", listingId), session.accessToken)
            if (result.isBlank() || result.trim() == "null")
                throw IllegalStateException("تمدید آگهی تأیید نشد")
            Unit
        }

    suspend fun resubmitMyListing(session: PelakSession, listingId: String) =
        withContext(Dispatchers.IO) {
            val result = perform("/rest/v1/rpc/pelak_resubmit_rejected_listing", "POST",
                JSONObject().put("p_listing_id", listingId), session.accessToken)
            if (result.isBlank() || result.trim() == "null")
                throw IllegalStateException("ارسال مجدد آگهی تأیید نشد")
            Unit
        }

    suspend fun messageById(session: PelakSession, id: String): ChatMessage? = withContext(Dispatchers.IO) {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return@withContext null
        parseMessages(perform("/rest/v1/pelak_listing_messages?select=id,listing_id,sender_id,recipient_id,body,created_at&id=eq.$id",
            jwt = session.accessToken)).firstOrNull()
    }

    suspend fun supportThreadForMessage(session: PelakSession, id: String): SupportThread? = withContext(Dispatchers.IO) {
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return@withContext null
        val rows = JSONArray(perform("/rest/v1/pelak_support_messages?select=thread_id&id=eq.$id", jwt = session.accessToken))
        val threadId = rows.optJSONObject(0)?.optString("thread_id") ?: return@withContext null
        supportThreads(session).firstOrNull { it.id == threadId }
    }

    suspend fun supportThreads(session: PelakSession): List<SupportThread> = withContext(Dispatchers.IO) {
        val rows = JSONArray(perform("/rest/v1/pelak_support_threads" +
            "?select=id,subject,status&user_id=eq.${session.userId}&hidden_for_user=eq.false&order=last_message_at.desc",
            jwt = session.accessToken))
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            SupportThread(row.getString("id"), row.getString("subject"), row.getString("status"))
        }
    }

    suspend fun supportMessages(session: PelakSession, thread: SupportThread): List<SupportMessage> =
        withContext(Dispatchers.IO) {
            val rows = JSONArray(perform("/rest/v1/pelak_support_messages" +
                "?select=sender_id,body&thread_id=eq.${thread.id}&order=created_at.asc,id.asc", jwt = session.accessToken))
            List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                SupportMessage(row.getString("sender_id") == session.userId, row.getString("body"))
            }
        }

    suspend fun createSupportThread(session: PelakSession, thread: SupportThread): Unit =
        withContext(Dispatchers.IO) {
            perform("/rest/v1/pelak_support_threads", "POST", JSONObject()
                .put("id", thread.id).put("user_id", session.userId).put("subject", thread.subject),
                session.accessToken, "resolution=ignore-duplicates,return=minimal")
        }

    suspend fun sendSupportMessage(session: PelakSession, thread: SupportThread, id: String, body: String): Unit =
        withContext(Dispatchers.IO) {
            perform("/rest/v1/pelak_support_messages", "POST", JSONObject().put("id", id)
                .put("thread_id", thread.id).put("sender_id", session.userId).put("body", body),
                session.accessToken, "resolution=ignore-duplicates,return=minimal")
        }

    suspend fun myShowroom(session: PelakSession): ShowroomProfile? = withContext(Dispatchers.IO) {
        val body = perform("/rest/v1/rpc/pelak_free_showroom_me", "POST", JSONObject(), session.accessToken)
        if (body.trim() == "null") return@withContext null
        val row = JSONObject(body)
        ShowroomProfile(row.optString("business_name"), row.optString("city"),
            if (row.isNull("public_phone")) "" else row.optString("public_phone"),
            if (row.isNull("bio")) "" else row.optString("bio"), row.optString("status"))
    }

    suspend fun saveShowroom(session: PelakSession, profile: ShowroomProfile): Unit =
        withContext(Dispatchers.IO) {
            perform("/rest/v1/rpc/pelak_free_showroom_save", "POST", JSONObject()
                .put("p_business_name", profile.name).put("p_city", profile.city)
                .put("p_public_phone", profile.phone).put("p_bio", profile.bio), session.accessToken)
        }

    suspend fun submitDraft(session: PelakSession, id: String): Unit = withContext(Dispatchers.IO) {
        perform("/rest/v1/rpc/pelak_submit_draft_for_review", "POST",
            JSONObject().put("p_listing_id", id), session.accessToken)
    }

    suspend fun registerPush(session: PelakSession, token: String) = withContext(Dispatchers.IO) {
        perform("/rest/v1/rpc/pelak_register_android_push", "POST",
            JSONObject().put("p_token", token), session.accessToken)
        Unit
    }
    suspend fun unregisterPush(session: PelakSession, token: String) = withContext(Dispatchers.IO) {
        perform("/rest/v1/rpc/pelak_unregister_android_push", "POST",
            JSONObject().put("p_token", token), session.accessToken)
        Unit
    }
}

