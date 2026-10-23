package io.github.xzdw.dylt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Largest stored edge of a picked publish icon. The bar draws it at roughly 28dp, so 128px keeps
 * it sharp on any screen while the base64 string stays small enough for the shared preferences the
 * module hands to the hooked app.
 */
private const val MAX_ICON_EDGE = 128

/**
 * Reads the picture at [uri] and returns it as a base64 PNG for `GlassConfig.publishIcon`:
 * centre-cropped to a square (the bar draws it inside a circle) and downscaled. Null when the
 * image cannot be decoded, so the caller can leave the setting untouched.
 */
internal fun encodePublishIcon(context: Context, uri: Uri): String? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_ICON_EDGE)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@runCatching null
    val side = minOf(decoded.width, decoded.height)
    if (side <= 0) {
        decoded.recycle()
        return@runCatching null
    }
    val square = if (side == decoded.width && side == decoded.height) {
        decoded
    } else {
        Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            .also { if (it !== decoded) decoded.recycle() }
    }
    val scaled = if (square.width <= MAX_ICON_EDGE) {
        square
    } else {
        Bitmap.createScaledBitmap(square, MAX_ICON_EDGE, MAX_ICON_EDGE, true)
            .also { if (it !== square) square.recycle() }
    }
    val bytes = ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    scaled.recycle()
    Base64.encodeToString(bytes, Base64.NO_WRAP)
}.getOrNull()

/** Power-of-two downscale so decoding a large gallery picture stays cheap. */
private fun sampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    var edge = max(width, height)
    while (edge / 2 >= target) {
        edge /= 2
        sample *= 2
    }
    return sample
}
