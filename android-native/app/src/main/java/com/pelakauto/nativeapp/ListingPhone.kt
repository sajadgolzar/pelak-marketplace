package com.pelakauto.nativeapp

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

internal object ListingPhone {
    private val numbers by lazy { PhoneNumberUtil.getInstance() }
    private val international = Regex("""^\+[1-9][0-9]{7,14}$""")
    private val separators = Regex("""[\s().\-\u00A0\u200E\u200F\u202F\u2066-\u2069]""")
    private val input = Regex("""^\+?[0-9]{1,17}$""")
    private val countries = mapOf(
        "IR" to listOf("ایران", "Iran"),
        "AE" to listOf("امارات", "امارات متحده عربی", "UAE", "United Arab Emirates"),
        "OM" to listOf("عمان", "Oman"),
        "GE" to listOf("گرجستان", "Georgia", "საქართველო"),
        "TR" to listOf("ترکیه", "Turkey", "Türkiye"),
        "IQ" to listOf("عراق", "Iraq"),
        "AM" to listOf("ارمنستان", "Armenia"),
        "AZ" to listOf("آذربایجان", "Azerbaijan")
    )
    private val aliases by lazy {
        buildMap {
            for (iso in Locale.getISOCountries()) {
                val locale = Locale("", iso)
                listOf(iso, locale.getDisplayCountry(Locale.ENGLISH),
                    locale.getDisplayCountry(Locale("fa")), locale.getDisplayCountry(Locale("ar")))
                    .forEach { put(countryKey(it), iso) }
            }
            for ((iso, names) in countries) names.forEach { put(countryKey(it), iso) }
        }
    }

    val preferredCountries: List<String> get() = countries.values.take(4).map { it.first() }

    private fun countryKey(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace('ي', 'ی').replace('ك', 'ک').replace("\u200c", "").replace(" ", "")

    fun region(country: String): String? = aliases[countryKey(country)]

    fun callingCode(region: String?): String? =
        region?.let { numbers.getCountryCodeForRegion(it).takeIf { code -> code > 0 } }?.let { "+$it" }

    fun normalize(number: String, region: String?): String? {
        val digits = number.map { char -> char.digitToIntOrNull()?.digitToChar() ?: char }
            .joinToString("").replace(separators, "")
        val value = if (digits.startsWith("00")) "+" + digits.drop(2) else digits
        if (!input.matches(value)) return null
        // Preserve explicitly international numbers, including older contacts accepted by the server.
        if (value.startsWith("+")) return value.takeIf(international::matches)
        if (region == null) return null
        return try {
            val parsed = numbers.parse(value, region)
            if (!numbers.isValidNumberForRegion(parsed, region)) null
            else numbers.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164).takeIf(international::matches)
        } catch (_: NumberParseException) {
            null
        }
    }

    fun nationalDisplay(number: String, region: String?): String {
        if (region == null || !number.startsWith("+")) return number
        return try {
            val parsed = numbers.parse(number, null)
            if (!numbers.isValidNumberForRegion(parsed, region)) number
            else numbers.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL).replace(separators, "")
        } catch (_: NumberParseException) {
            number
        }
    }
}
