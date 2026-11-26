package com.pelakauto.nativeapp

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.math.BigDecimal

private val TomanStorageLimit = BigDecimal("100000000000")
internal const val ListingPriceLimitMessage =
    "قیمت باید کمتر از 100,000,000,000 تومان باشد. مبلغ را به تومان وارد کن."

internal fun listingPriceExceedsStorage(price: BigDecimal?): Boolean =
    price != null && price.setScale(3, java.math.RoundingMode.HALF_UP) >= TomanStorageLimit

internal fun normalizeListingPrice(value: String): String? {
    val normalized = buildString {
        for (character in value) {
            val digit = Character.digit(character, 10)
            when {
                digit >= 0 -> append(digit)
                character == '.' || character == '٫' -> append('.')
                character == ',' || character == '٬' || character.isWhitespace() -> Unit
                else -> return null
            }
        }
    }
    return normalized.takeIf { it.matches(Regex("[0-9]*(\\.[0-9]*)?")) }
}

internal object ListingPriceGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val integerLength = text.text.indexOf('.').takeIf { it >= 0 } ?: text.length
        val separators = (1 until integerLength).filter { (integerLength - it) % 3 == 0 }
        val display = buildString {
            text.text.forEachIndexed { index, digit ->
                if (index in separators) append(',')
                append(digit)
            }
        }
        val displaySeparators = separators.mapIndexed { index, offset -> offset + index }
        return TransformedText(AnnotatedString(display), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + separators.count { it < offset }

            override fun transformedToOriginal(offset: Int): Int =
                offset - displaySeparators.count { it < offset }
        })
    }
}
