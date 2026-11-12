package com.pelakauto.nativeapp

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Locale

internal data class Car(
    val id: String,
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
    val imagePath: String?,
    val sellerPhone: String
) {
    fun priceLabel(): String {
        if (priceNegotiable || price == null) return "قیمت توافقی"
        return try {
            val amount = BigDecimal(price)
            val isIranianRial = currency == "IRR"
            val rendered = if (isIranianRial) amount.divide(BigDecimal.TEN) else amount
            NumberFormat.getNumberInstance(Locale("fa", "IR"))
                .format(rendered) + if (isIranianRial) " تومان" else " " + currency
        } catch (_: Exception) { "قیمت نامشخص" }
    }
}

internal data class PelakSession(val accessToken: String, val userId: String, val email: String)

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
            connection.connectTimeout = 12000
            connection.readTimeout = 12000
            connection.setRequestProperty("apikey", PelakConfig.PUBLIC_KEY)
            connection.setRequestProperty("Accept", "application/json")
            if (jwt != null) connection.setRequestProperty("Authorization", "Bearer " + jwt)
            if (data != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Prefer", "return=representation")
                connection.outputStream.use {
                    it.write(data.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = source?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("خطای سرور (" + code + ")")
            return result
        } finally { connection.disconnect() }
    }

    private val carFields = "id,title,make,model,trim,year,mileage_km,price,price_negotiable,currency,country,city,description,vehicle_status,listing_status,image_paths,seller_phone"

    private fun parseCars(response: String): List<Car> {
        val list = JSONArray(response)
        return (0 until list.length()).map { index ->
            val j = list.getJSONObject(index)
            val paths = j.optJSONArray("image_paths")
            Car(
                id = j.optString("id"),
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
                imagePath = if (paths != null && paths.length() > 0) paths.optString(0).takeIf { it.isNotBlank() } else null,
                sellerPhone = j.optString("seller_phone")
            )
        }
    }

    suspend fun published(): List<Car> = withContext(Dispatchers.IO) {
        parseCars(perform("/rest/v1/listings?select=" + carFields +
            "&listing_status=eq.published&order=created_at.desc&limit=100"))
    }

    suspend fun mine(session: PelakSession): List<Car> = withContext(Dispatchers.IO) {
        parseCars(perform("/rest/v1/listings?select=" + carFields +
            "&user_id=eq." + session.userId + "&order=created_at.desc&limit=100",
            jwt=session.accessToken))
    }

    suspend fun signedPhoto(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val response = perform("/storage/v1/object/sign/car-images/" + Uri.encode(path, "/"),
                "POST", JSONObject().put("expiresIn", 3600))
            val signedPath = JSONObject(response).optString("signedURL")
            if (signedPath.startsWith("/storage/v1/")) PelakConfig.API_ROOT + signedPath else null
        } catch (_: Exception) { null }
    }

    suspend fun sendOtp(email: String) = withContext(Dispatchers.IO) {
        perform("/auth/v1/otp", "POST", JSONObject()
            .put("email", email.trim())
            .put("create_user", true))
        Unit
    }

    suspend fun verifyOtp(email: String, token: String): PelakSession = withContext(Dispatchers.IO) {
        val result = JSONObject(perform("/auth/v1/verify", "POST", JSONObject()
            .put("type", "email")
            .put("email", email.trim())
            .put("token", token.trim())))
        val access = result.optString("access_token")
        if (access.isBlank()) throw IllegalStateException("تأیید ورود ناموفق بود")
        val user = JSONObject(perform("/auth/v1/user", jwt = access))
        val id = user.optString("id")
        if (id.isBlank()) throw IllegalStateException("شناسه کاربری تأیید نشد")
        PelakSession(access, id, email.trim())
    }

    suspend fun createDraft(
        session: PelakSession,
        title: String,
        make: String,
        model: String,
        year: Int,
        priceRials: String?,
        country: String,
        city: String,
        phone: String
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("user_id", session.userId)
            .put("title", title.trim())
            .put("make", make.trim())
            .put("model", model.trim())
            .put("year", year)
            .put("currency", "IRR")
            .put("country", country.trim())
            .put("city", city.trim())
            .put("vehicle_status", "ready")
            .put("listing_status", "draft")
            .put("seller_phone", phone.trim())
            .put("seller_contact_consent", true)
            .put("price_negotiable", priceRials == null)
            .put("price", if (priceRials == null) JSONObject.NULL else BigDecimal(priceRials))
        val result = perform("/rest/v1/listings", "POST", payload, session.accessToken)
        if (JSONArray(result).length() == 0) throw IllegalStateException("پیش‌نویس ذخیره نشد")
        Unit
    }
}
