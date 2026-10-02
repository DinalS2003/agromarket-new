package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

object ImageCompressor {

    private const val MAX_WIDTH = 1600
    private const val MAX_HEIGHT = 1600
    private const val TARGET_MAX_BYTES = 800 * 1024 // 800 KB

    /**
     * Compresses image from Uri to JPEG byte array under TARGET_MAX_BYTES.
     * Preserves orientation from EXIF metadata.
     */
    suspend fun compressImage(context: Context, imageUri: Uri): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // 1. Decode bounds
            var inputStream: InputStream? = contentResolver.openInputStream(imageUri)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            val srcWidth = options.outWidth
            val srcHeight = options.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Invalid image dimensions"))
            }

            // 2. Compute sample size
            var inSampleSize = 1
            if (srcHeight > MAX_HEIGHT || srcWidth > MAX_WIDTH) {
                val halfHeight = srcHeight / 2
                val halfWidth = srcWidth / 2
                while ((halfHeight / inSampleSize) >= MAX_HEIGHT && (halfWidth / inSampleSize) >= MAX_WIDTH) {
                    inSampleSize *= 2
                }
            }

            // 3. Decode scaled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                this.inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            inputStream = contentResolver.openInputStream(imageUri)
            val decodedBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            inputStream?.close()

            if (decodedBitmap == null) {
                return@withContext Result.failure(IllegalStateException("Could not decode bitmap from stream"))
            }

            var currentBitmap: Bitmap = decodedBitmap

            // 4. Correct EXIF orientation
            try {
                contentResolver.openInputStream(imageUri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                    if (rotationDegrees != 0f) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees) }
                        val rotated = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                        if (rotated != currentBitmap) {
                            currentBitmap.recycle()
                            currentBitmap = rotated
                        }
                    }
                }
            } catch (_: Exception) {}

            // 5. Scale down if still larger than max dimensions
            val currentMax = max(currentBitmap.width, currentBitmap.height)
            if (currentMax > MAX_WIDTH) {
                val ratio = MAX_WIDTH.toFloat() / currentMax
                val newWidth = (currentBitmap.width * ratio).toInt()
                val newHeight = (currentBitmap.height * ratio).toInt()
                val scaled = Bitmap.createScaledBitmap(currentBitmap, newWidth, newHeight, true)
                if (scaled != currentBitmap) {
                    currentBitmap.recycle()
                    currentBitmap = scaled
                }
            }

            // 6. Compress with quality steps
            var quality = 85
            var outputBytes: ByteArray
            do {
                val outputStream = ByteArrayOutputStream()
                currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputBytes = outputStream.toByteArray()
                quality -= 10
            } while (outputBytes.size > TARGET_MAX_BYTES && quality >= 40)

            currentBitmap.recycle()
            Result.success(outputBytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resizes picked listing photo to max 1024px and compresses to JPEG 80%.
     */
    suspend fun compressListingPhoto(
        context: Context,
        imageUri: Uri,
        maxDimension: Int = 1024,
        quality: Int = 80
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            var inputStream: InputStream? = contentResolver.openInputStream(imageUri) ?: return@withContext null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (originalBitmap == null) return@withContext null

            var currentBitmap = originalBitmap
            try {
                contentResolver.openInputStream(imageUri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val rotationDegrees = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                    if (rotationDegrees != 0f) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees) }
                        val rotated = Bitmap.createBitmap(currentBitmap, 0, 0, currentBitmap.width, currentBitmap.height, matrix, true)
                        if (rotated != currentBitmap) {
                            currentBitmap.recycle()
                            currentBitmap = rotated
                        }
                    }
                }
            } catch (_: Exception) {}

            val w = currentBitmap.width
            val h = currentBitmap.height
            val scaled = if (w > maxDimension || h > maxDimension) {
                val ratio = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
                Bitmap.createScaledBitmap(currentBitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
            } else {
                currentBitmap
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (scaled != currentBitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (!currentBitmap.isRecycled) {
                currentBitmap.recycle()
            }
            bytes
        } catch (_: Exception) {
            null
        }
    }
}
