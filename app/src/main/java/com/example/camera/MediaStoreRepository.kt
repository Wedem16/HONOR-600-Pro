package com.example.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedMediaResult(
    val uri: Uri,
    val displayName: String,
    val isVideo: Boolean,
    val relativePath: String
)

data class PendingVideoTarget(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val fileDescriptor: ParcelFileDescriptor
)

/**
 * Organizes captured photos into `Pictures/Honor600ProCam/` and videos into
 * `Movies/Honor600ProCam/` via Android MediaStore without requesting broad storage permissions.
 */
class MediaStoreRepository(private val context: Context) {

    fun buildPhotoFileName(width: Int, height: Int, modeTag: String): String {
        val ts = timestamp()
        val mp = ((width.toLong() * height.toLong()) / 1_000_000.0)
        val resTag = if (mp >= 1.0) String.format(Locale.US, "%.0fMP", mp) else "${width}x${height}"
        val cleanMode = sanitizeTag(modeTag)
        return "HONOR600_${ts}_${resTag}_${cleanMode}.jpg"
    }

    fun buildVideoFileName(resolutionTag: String, fps: Int, modeTag: String): String {
        val ts = timestamp()
        val cleanRes = sanitizeTag(resolutionTag)
        val cleanMode = sanitizeTag(modeTag)
        return "HONOR600_${ts}_${cleanRes}_${fps}FPS_${cleanMode}.mp4"
    }

    fun saveJpegPhoto(
        jpegBytes: ByteArray,
        width: Int,
        height: Int,
        modeTag: String
    ): SavedMediaResult {
        val displayName = buildPhotoFileName(width, height, modeTag)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$ALBUM_FOLDER"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore failed to create photo entry.")
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(jpegBytes)
                    stream.flush()
                } ?: throw IllegalStateException("Unable to open MediaStore photo output stream.")
                val doneValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                return SavedMediaResult(uri, displayName, isVideo = false, relativePath = relativePath)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM_FOLDER
            ).apply { mkdirs() }
            val outFile = File(dir, displayName)
            FileOutputStream(outFile).use { it.write(jpegBytes) }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, outFile.absolutePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: Uri.fromFile(outFile)
            return SavedMediaResult(uri, displayName, isVideo = false, relativePath = relativePath)
        }
    }

    fun prepareVideoOutput(
        resolutionTag: String,
        fps: Int,
        modeTag: String
    ): PendingVideoTarget {
        val displayName = buildVideoFileName(resolutionTag, fps, modeTag)
        val relativePath = "${Environment.DIRECTORY_MOVIES}/$ALBUM_FOLDER"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore failed to create video entry.")
            val pfd = resolver.openFileDescriptor(uri, "rw")
                ?: run {
                    resolver.delete(uri, null, null)
                    throw IllegalStateException("Unable to open MediaStore video FileDescriptor.")
                }
            return PendingVideoTarget(uri, displayName, relativePath, pfd)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                ALBUM_FOLDER
            ).apply { mkdirs() }
            val outFile = File(dir, displayName)
            val pfd = ParcelFileDescriptor.open(
                outFile,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, outFile.absolutePath)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: Uri.fromFile(outFile)
            return PendingVideoTarget(uri, displayName, relativePath, pfd)
        }
    }

    fun finalizeVideoOutput(target: PendingVideoTarget, succeeded: Boolean): SavedMediaResult? {
        runCatching { target.fileDescriptor.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            if (succeeded) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(target.uri, values, null, null)
            } else {
                resolver.delete(target.uri, null, null)
                return null
            }
        }
        return if (succeeded) {
            SavedMediaResult(
                uri = target.uri,
                displayName = target.displayName,
                isVideo = true,
                relativePath = target.relativePath
            )
        } else null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun sanitizeTag(raw: String): String =
        raw.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "_").trim('_').ifEmpty { "CINEMA" }

    companion object {
        const val ALBUM_FOLDER = "Honor600ProCam"
    }
}
