package app.truenascompanion.data.files

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi

/**
 * Where a downloaded NAS file is written on the phone (1.3.0).
 *
 * - [Document]: a file the user created in the system "Save as" dialog (Storage Access Framework).
 * - [Downloads]: "Save to Downloads" on Android 10+: a MediaStore Downloads entry (no storage permission needed).
 *   It is inserted as pending (hidden from other apps) and only published once the whole file has arrived; a cancelled
 *   or failed download deletes it again.
 *
 * On Android 8-9 "Save to Downloads" opens the "Save as" dialog in the Downloads folder instead (MediaStore Downloads
 * only exists from Android 10, and writing there directly would need the storage permission).
 */
sealed interface SaveTarget {
    val uri: Uri

    data class Document(override val uri: Uri) : SaveTarget
    data class Downloads(override val uri: Uri) : SaveTarget
}

object SaveTargets {
    /** True when "Save to Downloads" can write without asking (MediaStore Downloads, Android 10+). */
    fun mediaStoreDownloads(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.Q

    /**
     * MIME type to create the file with. The system providers (MediaStore and the SAF storage provider) append an
     * extension when the MIME type doesn't match the file's own extension (`config.yaml` + `text/plain` would become
     * `config.yaml.txt`), so this uses the platform's own type for the extension, else `application/octet-stream`,
     * which keeps the name unchanged.
     */
    fun saveMime(name: String, lookup: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }): String {
        val ext = FilePolicy.extension(name)
        return ext.takeIf { it.isNotEmpty() }?.let(lookup) ?: "application/octet-stream"
    }

    /** Start folder for the Android 8-9 fallback dialog (Downloads on the primary storage). */
    val downloadsInitialUri: Uri
        get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + Environment.DIRECTORY_DOWNLOADS)

    /** Creates a pending Downloads entry for [name]. Null if MediaStore refused. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun createDownload(resolver: ContentResolver, name: String): SaveTarget.Downloads? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, saveMime(name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)?.let { SaveTarget.Downloads(it) }
    }

    /** The download finished: make the Downloads entry visible. */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun publish(resolver: ContentResolver, target: SaveTarget.Downloads) {
        resolver.update(target.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** Removes a half-written file (cancelled or failed download). Never throws. */
    fun discard(resolver: ContentResolver, target: SaveTarget) {
        runCatching {
            when (target) {
                is SaveTarget.Document -> DocumentsContract.deleteDocument(resolver, target.uri)
                is SaveTarget.Downloads -> resolver.delete(target.uri, null, null)
            }
        }
    }

    /** Name the file finally got (MediaStore adds " (1)" when Downloads already has one with that name). */
    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
