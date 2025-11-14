package com.roderickqiu.seenot.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

object BitmapUtils {
    fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    suspend fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        appName: String?,
        reason: String
    ) {
        try {
            val appNameDisplay = appName ?: "Unknown"
            val timestamp = System.currentTimeMillis()
            val displayName = "SeeNot_${appNameDisplay}_${timestamp}_$reason.jpg"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SeeNot")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: run {
                Log.e("BitmapUtils", "Failed to create MediaStore entry")
                return
            }

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            } ?: run {
                Log.e("BitmapUtils", "Failed to open output stream")
                context.contentResolver.delete(uri, null, null)
                return
            }

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            Log.d("BitmapUtils", "Screenshot saved to gallery: $displayName")
        } catch (e: Exception) {
            Log.e("BitmapUtils", "Error saving screenshot to gallery", e)
        }
    }
}

