package com.facefusion.app.data.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.facefusion.app.domain.result.AppResult
import com.facefusion.app.domain.result.ErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MediaManager(private val context: Context) {
    fun isImageUri(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            return mime == "image/jpeg" || mime == "image/jpg" || mime == "image/png"
        }
        val ext = extensionFromName(getDisplayName(uri))
        return ext == ".jpg" || ext == ".jpeg" || ext == ".png"
    }

    fun isVideoUri(uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase()
        if (mime != null) {
            return mime == "video/mp4" || mime == "video/quicktime"
        }
        val ext = extensionFromName(getDisplayName(uri))
        return ext == ".mp4" || ext == ".mov"
    }

    fun isTargetAllowed(uri: Uri, isPhotoVideoMode: Boolean): Boolean {
        return if (isPhotoVideoMode) isVideoUri(uri) else isImageUri(uri)
    }

    fun targetErrorText(isPhotoVideoMode: Boolean): String {
        return if (isPhotoVideoMode) "video (mp4/mov)" else "photo (jpg/jpeg/png)"
    }

    fun extractVideoPreviewFrame(uri: Uri) = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        frame
    }.getOrNull()

    fun getMime(uri: Uri): String? = context.contentResolver.getType(uri)

    suspend fun copyToCache(uri: Uri, prefix: String, mime: String?): AppResult<File> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = extensionFromName(getDisplayName(uri)) ?: extensionFromMime(mime) ?: ""
            val name = if (ext.isNotEmpty()) {
                "${prefix}_${System.currentTimeMillis()}$ext"
            } else {
                "${prefix}_${System.currentTimeMillis()}"
            }
            val file = File(context.cacheDir, name)
            val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open file")
            input.use { stream ->
                FileOutputStream(file).use { output ->
                    stream.copyTo(output)
                }
            }
            file
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Error(ErrorType.UNKNOWN, it.message, it) },
        )
    }

    suspend fun saveImageToGallery(imageFile: File): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = when (imageFile.extension.lowercase()) {
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            val filename = "faceswap_${System.currentTimeMillis()}.${imageFile.extension.ifBlank { "jpg" }}"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FaceFusion")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create gallery item")
            resolver.openOutputStream(uri)?.use { output ->
                imageFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open gallery stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error(ErrorType.UNKNOWN, it.message, it) },
        )
    }

    suspend fun saveVideoToGallery(videoFile: File): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = when (videoFile.extension.lowercase()) {
                "mov" -> "video/quicktime"
                else -> "video/mp4"
            }
            val ext = videoFile.extension.ifBlank { "mp4" }
            val filename = "faceswap_${System.currentTimeMillis()}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FaceFusion")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create gallery item")
            resolver.openOutputStream(uri)?.use { output ->
                videoFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open gallery stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
            Unit
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error(ErrorType.UNKNOWN, it.message, it) },
        )
    }

    private fun getDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return it.getString(index)
                }
            }
        }
        return null
    }

    private fun extensionFromName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val dot = name.lastIndexOf('.')
        if (dot == -1 || dot == name.length - 1) return null
        return name.substring(dot).lowercase()
    }

    private fun extensionFromMime(mime: String?): String? {
        return when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "video/mp4" -> ".mp4"
            "video/quicktime" -> ".mov"
            else -> null
        }
    }
}
