package com.pelakauto.nativeapp

import android.net.Uri
import java.math.BigDecimal

internal data class ListingFilters(
    val brand: String = "", val city: String = "", val minYear: String = "", val maxYear: String = "",
    val minMileage: String = "", val maxMileage: String = "", val minPrice: String = "", val maxPrice: String = "",
    val currency: String = "IRR", val status: String = "همه", val negotiable: Boolean = false,
    val photos: Boolean = false, val sort: String = "جدیدترین"
) {
    fun parameters(): String = buildString {
        fun clean(value: String) = value.map { it.digitToIntOrNull()?.digitToChar() ?: it }.joinToString("")
        fun text(value: String) = Uri.encode(value.replace(Regex("[^\\p{L}\\p{N} \\-]"), "").trim().take(80))
        fun bound(field: String, op: String, value: String, multiplier: BigDecimal = BigDecimal.ONE) {
            val number = clean(value).toBigDecimalOrNull()
            if (number != null && number >= BigDecimal.ZERO) append("&$field=$op.${number.multiply(multiplier).toPlainString()}")
        }
        if (brand.isNotBlank()) {
            val value = text(brand)
            append("&or=(make.ilike.*$value*,model.ilike.*$value*,trim.ilike.*$value*,title.ilike.*$value*)")
        }
        if (city.isNotBlank()) {
            val value = text(city)
            append("&or=(city.ilike.*$value*,country.ilike.*$value*)")
        }
        bound("year", "gte", minYear); bound("year", "lte", maxYear)
        bound("mileage_km", "gte", minMileage); bound("mileage_km", "lte", maxMileage)
        val currencyActive = minPrice.isNotBlank() || maxPrice.isNotBlank() || sort in listOf("ارزان‌ترین", "گران‌ترین")
        if (currencyActive) {
            append("&currency=eq.${currency.takeIf { it in listOf("IRR","USD","EUR","AED","OMR","GEL") } ?: "IRR"}")
            val factor = if (currency == "IRR") BigDecimal.TEN else BigDecimal.ONE
            bound("price", "gte", minPrice, factor); bound("price", "lte", maxPrice, factor)
        }
        if (status in listOf("ready","abroad","in_transit","customs")) append("&vehicle_status=eq.$status")
        if (negotiable) append("&price_negotiable=eq.true")
        if (photos) append("&image_paths=neq.%7B%7D")
        val order = when (sort) {
            "ارزان‌ترین" -> "price.asc.nullslast"
            "گران‌ترین" -> "price.desc.nullslast"
            "کم‌کارکردترین" -> "mileage_km.asc.nullslast"
            "جدیدترین مدل" -> "year.desc"
            else -> "created_at.desc"
        }
        append("&order=$order,id.desc")
    }
}
