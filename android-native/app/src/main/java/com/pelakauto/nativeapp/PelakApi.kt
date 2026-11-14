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

internal class PelakApi {
    private fun perform(
        route: String,
        method: String = "GET",
        data: JSONObject? = null,
        jwt: String? = null
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
                connection.setRequestProperty("Prefer", "return=representation")
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
    suspend fun published(query: String = "", offset: Int = 0, pageSize: Int = 100): List<Car> =
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
                "&order=created_at.desc&limit=" + pageSize.coerceIn(1,100) +
                "&offset=" + offset.coerceAtLeast(0)))
        }
    suspend fun mine(session: PelakSession): List<Car> = withContext(Dispatchers.IO) {
        parseCars(perform("/rest/v1/listings?select=" + carFields +
            "&user_id=eq." + session.userId + "&order=created_at.desc&limit=100",
            jwt = session.accessToken))
    }
    // The Storage API returns /object/sign/... on some versions and /storage/v1/object/sign/... on others.
    suspend fun signedPhoto(path: String, jwt: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val response = perform("/storage/v1/object/sign/car-images/" + Uri.encode(path, "/"),
                "POST", JSONObject().put("expiresIn", 900), jwt)
            val signed = JSONObject(response).optString("signedURL")
            when {
                signed.startsWith("/storage/v1/") -> PelakConfig.API_ROOT + signed
                signed.startsWith("/object/sign/") -> PelakConfig.API_ROOT + "/storage/v1" + signed
                signed.startsWith("https://" + Uri.parse(PelakConfig.API_ROOT).host + "/storage/v1/") -> signed
                else -> null
            }
        } catch (_: Exception) { null }
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
        country: String, city: String, phone: String
    ): JSONObject = JSONObject()
        .put("title", title.trim()).put("make", make.trim()).put("model", model.trim())
        .put("year", year).put("currency", "IRR")
        .put("country", country.trim()).put("city", city.trim())
        .put("vehicle_status", "ready").put("seller_phone", phone.trim())
        .put("seller_contact_consent", true)
        .put("price_negotiable", priceRials == null)
        .put("price", if (priceRials == null) JSONObject.NULL else BigDecimal(priceRials))
    suspend fun createDraft(
        session: PelakSession, title: String, make: String, model: String,
        year: Int, priceRials: String?, country: String, city: String, phone: String
    ): String = withContext(Dispatchers.IO) {
        val payload = listingPayload(title, make, model, year, priceRials, country, city, phone)
            .put("user_id", session.userId).put("listing_status", "draft")
        val row = JSONArray(perform("/rest/v1/listings", "POST", payload, session.accessToken))
        if (row.length() == 0) throw IllegalStateException("پیش‌نویس ذخیره نشد")
        row.getJSONObject(0).getString("id")
    }
    suspend fun updateDraft(
        session: PelakSession, id: String, title: String, make: String,
        model: String, year: Int, priceRials: String?, country: String, city: String, phone: String
    ) = withContext(Dispatchers.IO) {
        val payload = listingPayload(title, make, model, year, priceRials, country, city, phone)
        val result = JSONArray(perform("/rest/v1/listings?id=eq." + Uri.encode(id) +
            "&user_id=eq." + session.userId + "&listing_status=eq.draft",
            "PATCH", payload, session.accessToken))
        if (result.length() != 1) throw IllegalStateException("فقط پیش‌نویس خودت قابل ویرایش است")
        Unit
    }
    suspend fun attachPhotos(session: PelakSession, id: String, paths: List<String>) =
        withContext(Dispatchers.IO) {
            val arr = JSONArray()
            paths.forEach { arr.put(it) }
            val result = JSONArray(perform("/rest/v1/listings?id=eq." + Uri.encode(id) +
                "&user_id=eq." + session.userId + "&listing_status=eq.draft",
                "PATCH", JSONObject().put("image_paths", arr), session.accessToken))
            if (result.length() != 1) throw IllegalStateException("اتصال عکس‌ها به آگهی انجام نشد")
            Unit
        }
    suspend fun uploadPhoto(session: PelakSession, resolver: ContentResolver, image: Uri): String =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(image)
                ?: throw IllegalStateException("عکس قابل خواندن نیست")
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            // decodeStream returns null deliberately when inJustDecodeBounds is true.
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("تصویر نامعتبر")
            var sample = 1
            while (bounds.outWidth / sample > 1700 || bounds.outHeight / sample > 1700) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = resolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalStateException("بازکردن عکس ممکن نیست")
            val buffer = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, buffer) }
            finally { bitmap.recycle() }
            val bytes = buffer.toByteArray()
            if (bytes.isEmpty() || bytes.size > 6 * 1024 * 1024) throw IllegalStateException("اندازه عکس مجاز نیست")
            val path = session.userId + "/" + UUID.randomUUID().toString() + ".jpg"
            val conn = URL(PelakConfig.API_ROOT + "/storage/v1/object/car-images/" +
                Uri.encode(path, "/")).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("apikey", PelakConfig.PUBLIC_KEY)
                conn.setRequestProperty("Authorization", "Bearer " + session.accessToken)
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.setRequestProperty("x-upsert", "false")
                conn.doOutput = true
                conn.outputStream.use { it.write(bytes) }
                if (conn.responseCode !in 200..299) throw IllegalStateException("آپلود عکس ناموفق: " + conn.responseCode)
            } finally { conn.disconnect() }
            path
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
