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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

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

internal fun preparePhoto(resolver: ContentResolver, image: Uri, rotation: Int): ByteArray {
    val orientation = resolver.openInputStream(image)?.use {
        runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: throw IllegalStateException("عکس قابل خواندن نیست")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "فرمت عکس قابل خواندن نیست" }
    var sample = 1
    while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1920) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val source = resolver.openInputStream(image)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalStateException("بازکردن عکس ممکن نیست")
    val matrix = orientationMatrix(orientation, rotation)
    val oriented = try {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    } catch (failure: Throwable) {
        source.recycle()
        throw failure
    }
    try {
        val stream = ByteArrayOutputStream()
        check(oriented.compress(Bitmap.CompressFormat.JPEG, 85, stream))
        return stream.toByteArray().also { require(it.isNotEmpty() && it.size <= 6 * 1024 * 1024) }
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
