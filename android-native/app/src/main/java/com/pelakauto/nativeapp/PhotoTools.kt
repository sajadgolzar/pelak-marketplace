package com.pelakauto.nativeapp

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

// Start no more than two transfers; return outcomes in selected order, not completion order.
internal suspend fun <T> uploadPhotoPair(
    pair: List<DraftPhoto>,
    upload: suspend (DraftPhoto) -> T
): List<Pair<DraftPhoto, Result<T>>> = kotlinx.coroutines.coroutineScope {
    require(pair.size <= 2)
    pair.map { photo ->
        async {
            try {
                photo to Result.success(upload(photo))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                photo to Result.failure<T>(failure)
            }
        }
    }.map { it.await() }
}

internal suspend fun uploadPhotoBatch(
    pending: List<DraftPhoto>,
    onProgress: (Int, Int) -> Unit,
    upload: suspend (DraftPhoto) -> Unit,
    onFailure: (DraftPhoto) -> Unit
) {
    for ((index, photo) in pending.withIndex()) {
        onProgress(index + 1, pending.size)
        try { upload(photo) }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { onFailure(photo) }
    }
}

internal data class DraftPhoto(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri? = null,
    val path: String? = null,
    val rotation: Int = 0
)

internal val DraftPhotoListSaver = androidx.compose.runtime.saveable.listSaver<List<DraftPhoto>, String>(
    save = { photos ->
        photos.flatMap { listOf(it.id, it.uri?.toString().orEmpty(), it.path.orEmpty(), it.rotation.toString()) }
    },
    restore = { values ->
        values.chunked(4).map { (id, uri, path, rotation) ->
            DraftPhoto(id, uri.takeIf(String::isNotEmpty)?.let(Uri::parse),
                path.takeIf(String::isNotEmpty), rotation.toInt())
        }
    }
)

internal fun orientationMatrix(orientation: Int, extraRotation: Int = 0): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
    }
    postRotate(extraRotation.toFloat())
}

// A slow or remote gallery provider must not block the entire draft forever.
private val photoPreparationWorkers = java.util.concurrent.ThreadPoolExecutor(
    2, 2, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
    java.util.concurrent.SynchronousQueue<Runnable>()
)

internal suspend fun preparePhotoBounded(resolver: ContentResolver, image: Uri, rotation: Int): ByteArray =
    kotlinx.coroutines.withTimeoutOrNull(25000L) {
        kotlinx.coroutines.suspendCancellableCoroutine<ByteArray> { continuation ->
            val task = java.util.concurrent.FutureTask<Unit> {
                val result = runCatching { preparePhoto(resolver, image, rotation) }
                if (continuation.isActive) continuation.resumeWith(result)
            }
            continuation.invokeOnCancellation { task.cancel(true) }
            try { photoPreparationWorkers.execute(task) }
            catch (failure: java.util.concurrent.RejectedExecutionException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(
                    IllegalStateException("گالری پاسخ نمی‌دهد؛ عکس را جایگزین کن یا دوباره تلاش کن.")))
            }
        }
    } ?: throw java.io.IOException("خواندن عکس بیش از حد طول کشید؛ دوباره تلاش کن.")

internal fun preparePhoto(resolver: ContentResolver, image: Uri, rotation: Int): ByteArray {
    // Read the provider only once; repeated opens may stall cloud-backed gallery items.
    val raw = resolver.openInputStream(image)?.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(32768)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            require(output.size() + n <= 32 * 1024 * 1024) { "حجم فایل عکس بیش از حد مجاز است" }
            output.write(chunk, 0, n)
        }
        output.toByteArray()
    } ?: throw IllegalStateException("عکس قابل خواندن نیست")
    val orientation = runCatching {
        ExifInterface(java.io.ByteArrayInputStream(raw)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "فرمت عکس قابل خواندن نیست" }
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val source = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
        ?: throw IllegalStateException("بازکردن عکس ممکن نیست")
    val oriented = try {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, orientationMatrix(orientation, rotation), true)
    } catch (failure: Throwable) {
        source.recycle()
        throw failure
    }
    try {
        val stream = ByteArrayOutputStream()
        for (quality in listOf(82, 72, 60)) {
            stream.reset()
            check(oriented.compress(Bitmap.CompressFormat.JPEG, quality, stream))
            if (stream.size() in 1..(1024 * 1024)) return stream.toByteArray()
        }
        require(stream.size() in 1..(6 * 1024 * 1024))
        return stream.toByteArray()
    } finally {
        if (oriented !== source) oriented.recycle()
        source.recycle()
    }
}

@Composable
internal fun PhotoGallery(paths: List<String>, title: String, jwt: String?, initial: Int = 0) {
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, (paths.size - 1).coerceAtLeast(0)),
        pageCount = { paths.size })
    val api = remember { PelakApi() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage, paths, jwt) {
        for (index in listOf(pager.currentPage - 1, pager.currentPage + 1)) {
            val path = paths.getOrNull(index) ?: continue
            val url = api.signedPhoto(path, jwt) ?: continue
            coil.Coil.imageLoader(context).enqueue(ImageRequest.Builder(context).data(url)
                .memoryCacheKey(photoCacheKey(path, jwt)).diskCacheKey(photoCacheKey(path, jwt)).size(1400).build())
        }
    }
    Column {
        HorizontalPager(pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxWidth().height(330.dp)) { page ->
            CarPhoto(paths[page], title, jwt, Modifier.fillMaxSize(),
                androidx.compose.ui.layout.ContentScale.Fit)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = pager.currentPage > 0, onClick = {
                scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
            }) { Text("قبلی") }
            Text("عکس ${pager.currentPage + 1} از ${paths.size}")
            TextButton(enabled = pager.currentPage < paths.lastIndex, onClick = {
                scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }) { Text("بعدی") }
        }
    }
}

internal fun photoCacheKey(path: String, jwt: String?): String {
    // Keep signed-in private images isolated from public image caches and other sessions.
    val scope = if (jwt == null) "public" else java.security.MessageDigest.getInstance("SHA-256")
        .digest(jwt.toByteArray()).joinToString("") { "%02x".format(it) }
    return "$scope:$path"
}
