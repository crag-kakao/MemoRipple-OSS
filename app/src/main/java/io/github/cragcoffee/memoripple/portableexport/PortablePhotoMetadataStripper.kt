package io.github.cragcoffee.memoripple.portableexport

import android.media.ExifInterface
import java.io.File

/**
 * 写真の位置情報などを取り除く — the option's whole mechanism. Platform [ExifInterface] only
 * (no new dependency), which writes JPEG: the sensitive tags are nulled and the metadata
 * segment rewritten in place, while the compressed image data — and so the picture itself —
 * never changes. Orientation stays, because a photo that suddenly lies on its side protects
 * nobody. Formats the platform cannot rewrite (PNG, WebP, HEIC…) pass through unchanged and
 * the contract says so in plain words.
 */
object PortablePhotoMetadataStripper {

    fun supports(mimeType: String?): Boolean =
        mimeType?.lowercase()?.trim() in setOf("image/jpeg", "image/jpg")

    /**
     * Strips [file] in place. Returns true when the rewrite succeeded; false means the file
     * still holds its original bytes (the caller decides whether that deserves a warning).
     */
    fun stripInPlace(file: File): Boolean = try {
        val exif = ExifInterface(file.absolutePath)
        SENSITIVE_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
        exif.saveAttributes()
        true
    } catch (failure: Exception) {
        false
    }

    /** What leaves with a camera photo and should not leave with an export. */
    private val SENSITIVE_TAGS = listOf(
        // Where.
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        // When.
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        // With what, and by whom.
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )
}
