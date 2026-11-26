package com.pelakauto.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingFormTest {
    @Test fun localNumbersUseTheListingCountry() {
        assertEquals("+989112345678", ListingPhone.normalize("۰۹۱۱۲۳۴۵۶۷۸", ListingPhone.region("ایران")))
        assertEquals("+989112345678", ListingPhone.normalize("٠٩١١٢٣٤٥٦٧٨", ListingPhone.region("IR")))
        assertEquals("+971501234567", ListingPhone.normalize("050 123 4567", ListingPhone.region("امارات")))
        assertEquals("+96892123456", ListingPhone.normalize("92123456", ListingPhone.region("عمان")))
        assertEquals("+995555123456", ListingPhone.normalize("555123456", ListingPhone.region("گرجستان")))
    }

    @Test fun explicitInternationalNumbersAndExistingListingsStayCompatible() {
        assertEquals("+989112345678", ListingPhone.normalize("+۹۸ ۹۱۱ ۲۳۴ ۵۶۷۸", "AE"))
        assertEquals("+989112345678", ListingPhone.normalize("00989112345678", null))
        val national = ListingPhone.nationalDisplay("+989112345678", "IR")
        assertEquals("09112345678", national)
        assertEquals("+989112345678", ListingPhone.normalize(national, "IR"))
        assertNull(ListingPhone.normalize("09112345678", null))
        assertNull(ListingPhone.normalize("09112", "IR"))
    }

    @Test fun brandSuggestionsMatchPersianEnglishModelsAndTrims() {
        fun terms(vararg values: String) = values.map(::vehicleSearchKey)
        val brands = listOf(
            VehicleBrand("کیا", terms("کیا", "Kia"), terms("اسپورتیج")),
            VehicleBrand("پژو", terms("پژو", "Peugeot"), terms("۲۰۷", "Peugeot 207")),
            VehicleBrand("تویوتا", terms("تویوتا", "Toyota"), terms("کمری", "Camry")),
            VehicleBrand("مزدا", terms("مزدا", "Mazda"), terms("Grand Touring"))
        )
        assertEquals(listOf("کیا"), matchingBrands("كيا", brands))
        assertEquals(listOf("پژو"), matchingBrands("Peugeot ۲۰۷", brands))
        assertEquals(listOf("تویوتا"), matchingBrands("camry", brands))
        assertEquals(listOf("مزدا"), matchingBrands("grand touring", brands))
        assertTrue(matchingBrands(" ", brands).isEmpty())
    }
    @Test fun listingPriceSupportsPastedDigitsAndStorageLimit() {
        assertEquals("1234567.5", normalizeListingPrice("۱٬۲۳۴٬۵۶۷٫۵"))
        assertNull(normalizeListingPrice("1e12"))
        assertTrue(listingPriceExceedsStorage("1200000000000".toBigDecimal()))
        assertTrue(listingPriceExceedsStorage("99999999999.9999".toBigDecimal()))
        assertFalse(listingPriceExceedsStorage("99999999999.999".toBigDecimal()))
        val price = ListingPriceGrouping.filter(androidx.compose.ui.text.AnnotatedString("1234567.5"))
        assertEquals("1,234,567.5", price.text.text)
        for (offset in 0..9) {
            assertEquals(offset, price.offsetMapping.transformedToOriginal(
                price.offsetMapping.originalToTransformed(offset)))
        }
    }
}
