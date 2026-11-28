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

    @Test fun pairedUploadsOverlapPreserveOrderAndIsolateFailures() = runBlocking {
        val photos = List(8) { DraftPhoto() }
        var active = 0
        var peak = 0
        val outcomes = photos.chunked(2).flatMap { pair ->
            uploadPhotoPair(pair) { photo ->
                active++
                peak = maxOf(peak, active)
                try {
                    kotlinx.coroutines.delay(if (photo == pair.first()) 20L else 1L)
                    if (photo == photos[2]) error("Simulated network failure")
                    photo.id
                } finally { active-- }
            }
        }
        assertEquals(2, peak)
        assertEquals(photos.map { it.id }, outcomes.map { it.first.id })
        assertEquals(7, outcomes.count { it.second.isSuccess })
        assertTrue(outcomes[2].second.isFailure)
        assertEquals(photos[2].id, uploadPhotoPair(listOf(photos[2])) { it.id }.single().second.getOrThrow())
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

    @Test fun boundedPhotoPreparationReturnsValidJpegAndReportsUnreadableFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "bounded-input.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val bytes = preparePhotoBounded(context.contentResolver, Uri.fromFile(file), 90)
        val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(80, result.width)
        assertEquals(120, result.height)
        result.recycle()
        val missing = File(context.cacheDir, "missing-upload-test.jpg")
        assertTrue(runCatching { preparePhotoBounded(context.contentResolver, Uri.fromFile(missing), 0) }.isFailure)
    }

    @Test fun photoRecoveryRequiresExactFilenameAndByteCount() {
        val rows = """[{"name":"photo.jpg","metadata":{"size":200704}},{"name":"other.jpg","metadata":{"size":100}}]"""
        assertTrue(matchesUploadedPhoto(rows, "photo.jpg", 200704))
        assertFalse(matchesUploadedPhoto(rows, "photo.jpg", 200705))
        assertFalse(matchesUploadedPhoto(rows, "other.jpg", 200704))
        assertFalse(matchesUploadedPhoto(rows, "oto.jpg", 200704))
        assertFalse(matchesUploadedPhoto("{}", "photo.jpg", 200704))
        assertFalse(matchesUploadedPhoto("""[{"name":"photo.jpg"}]""", "photo.jpg", 200704))
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
