package com.pelakauto.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoFlowTest {
    @Test fun failedPhotoDoesNotStopTheOtherSevenAndRetryKeepsTheirIds() = runBlocking {
        val photos = List(8) { DraftPhoto() }
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        uploadPhotoBatch(photos, { _, _ -> }, { photo ->
            if (photo == photos[2]) error("Network timeout")
            completed += photo.id
        }, { failed += it.id })
        assertEquals(7, completed.size)
        assertEquals(listOf(photos[2].id), failed)
        uploadPhotoBatch(photos.filter { it.id in failed }, { _, _ -> }, { completed += it.id }, { fail() })
        assertEquals(8, completed.distinct().size)
        assertEquals(photos.map { it.id }.toSet(), completed.toSet())
    }

    @Test fun encodingHonorsAllExifOrientationsAndDropsMetadata() {
        val context = RuntimeEnvironment.getApplication() as Context
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val source = File(context.cacheDir, "orientation-input.jpg")
        FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        for (orientation in 1..8) {
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val bytes = preparePhoto(context.contentResolver, Uri.fromFile(source), 0)
            val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(result)
            assertEquals(if (orientation in 5..8) 80 else 120, result.width)
            assertEquals(if (orientation in 5..8) 120 else 80, result.height)
            result.recycle()
            assertEquals(0, ExifInterface(bytes.inputStream()).rotationDegrees)
        }
        val rotated = preparePhoto(context.contentResolver, Uri.fromFile(source), 90)
        val result = BitmapFactory.decodeByteArray(rotated, 0, rotated.size)
        assertEquals(120, result.width)
        assertEquals(80, result.height)
        result.recycle()
    }

    @Test fun serverFiltersContainRangesAndStableSortBeforePagination() {
        val query = ListingFilters(minPrice = "۵۰۰۰۰۰۰۰۰", maxYear = "۲۰۲۶",
            currency = "IRR", photos = true, sort = "ارزان‌ترین").parameters()
        assertTrue(query.contains("price=gte.5000000000"))
        assertTrue(query.contains("year=lte.2026"))
        assertTrue(query.contains("currency=eq.IRR"))
        assertTrue(query.contains("order=price.asc.nullslast,id.desc"))
        assertTrue(query.contains("image_paths=neq."))
    }
}
