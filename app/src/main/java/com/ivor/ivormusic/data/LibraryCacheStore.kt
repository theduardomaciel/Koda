package com.ivor.ivormusic.data

import android.content.Context
import android.util.AtomicFile
import com.ivor.ivormusic.util.KLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException

/**
 * The signed-in account's Library lists as they were the last time a fetch
 * succeeded.
 *
 * Signed out there is nothing to cache: the device library, saved references
 * and downloads are already local. Signed in, these three lists live only in
 * [com.ivor.ivormusic.ui.home.HomeViewModel]'s memory, so without a snapshot a
 * cold start without a connection draws three tabs that look empty. The
 * account is still the source of truth; this is only what it said last time.
 *
 * Deliberately not backed up (it is absent from [BackupRepository]'s
 * allowlists): it can always be fetched again, and a restore should not carry
 * one account's library onto a device that has not signed in.
 */
@Serializable
data class LibraryCacheSnapshot(
    val savedAtMs: Long = 0L,
    val playlists: List<PlaylistDisplayItem> = emptyList(),
    val albums: List<PlaylistDisplayItem> = emptyList(),
    val artists: List<ArtistItem> = emptyList(),
)

/**
 * One JSON snapshot per profile, written atomically.
 *
 * The file name follows the repo's per-profile convention
 * ([ProfileManager.historyScopedFileName]), so an id restored from a backup
 * cannot escape `filesDir` and the legacy profile keeps the unsuffixed name.
 * Writes are rare - after a successful library load and after a save/follow -
 * so there is no need for a row store or a change feed.
 */
class LibraryCacheStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(profileId: String): LibraryCacheSnapshot? {
        val file = AtomicFile(fileFor(appContext, profileId))
        return try {
            file.openRead().bufferedReader().use { json.decodeFromString(it.readText()) }
        } catch (_: FileNotFoundException) {
            null
        } catch (error: Exception) {
            KLog.e(TAG, "Cannot read the cached library", error)
            // A malformed snapshot is useless and would log on every launch.
            runCatching { file.delete() }
            null
        }
    }

    fun save(profileId: String, snapshot: LibraryCacheSnapshot) {
        synchronized(LOCK) {
            val file = AtomicFile(fileFor(appContext, profileId))
            val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                KLog.e(TAG, "Cannot write the cached library", error)
            }
        }
    }

    fun clear(profileId: String) {
        synchronized(LOCK) {
            // AtomicFile.delete removes the backup as well as the base file,
            // so a half-written snapshot cannot come back.
            runCatching { AtomicFile(fileFor(appContext, profileId)).delete() }
        }
    }

    companion object {
        private const val TAG = "LibraryCacheStore"
        internal const val FILE_BASE = "library_cache"
        private val LOCK = Any()

        internal fun fileFor(context: Context, profileId: String): File = File(
            context.applicationContext.filesDir,
            ProfileManager.historyScopedFileName(
                FILE_BASE, "json", profileId, context.applicationContext
            )
        )
    }
}
