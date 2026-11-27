package com.pelakauto.nativeapp
import org.junit.Assert.assertEquals
import org.junit.Test
class ListingPresentationTest {
    @Test fun statusesAreLocalized() {
        assertEquals("پیش‌نویس", listingStatusLabel("draft"))
        assertEquals("نیازمند اصلاح", listingStatusLabel("draft", true))
        assertEquals("در انتظار بررسی", listingStatusLabel("pending_review"))
        assertEquals("وضعیت نامشخص", listingStatusLabel("unexpected"))
    }
    @Test fun mileageUsesConsistentLatinGrouping() {
        assertEquals("12,000 کیلومتر", listingMileageLabel(12000))
        assertEquals("0 کیلومتر", listingMileageLabel(0))
    }
}
