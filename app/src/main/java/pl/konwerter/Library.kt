package pl.konwerter

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class Vid(val id: Long, val path: String, val title: String, val folder: String, val duration: Long, val size: Long,
          val w: Int, val h: Int, val date: Long, val content: Uri) {
    fun track() = Track(path.ifEmpty { content.toString() }, title, folder, true, content.toString())
}

class Song(val id: Long, val path: String, val title: String, val artist: String, val album: String, val albumId: Long,
           val duration: Long, val folder: String, val content: Uri) {
    fun track() = Track(path.ifEmpty { content.toString() }, title, artist, false, content.toString())
}

/** Biblioteka multimediów telefonu (MediaStore) + miniatury. */
object Library {
    var videos: List<Vid> = emptyList()
    var songs: List<Song> = emptyList()
    var loaded = false
    private val io = Executors.newFixedThreadPool(3)
    private val thumbs = LruCache<String, Bitmap>(120)
    private val missing = HashSet<String>()

    fun time(ms: Long): String {
        val s = maxOf(0L, ms / 1000)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun readable(p: String?): String = if (p != null && File(p).canRead()) p else ""

    fun load(ctx: Context) {
        val cr = ctx.contentResolver
        val v = ArrayList<Vid>()
        try {
            cr.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED),
                null, null, MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    v.add(Vid(id, readable(c.getString(2)), c.getString(1) ?: "film", c.getString(3) ?: "Inne", c.getLong(4), c.getLong(5),
                        c.getInt(6), c.getInt(7), c.getLong(8), ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)))
                }
            }
        } catch (e: Exception) {
        }
        val s = ArrayList<Song>()
        try {
            cr.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.BUCKET_DISPLAY_NAME),
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null, MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val artist = c.getString(3)?.takeIf { it != "<unknown>" } ?: "Nieznany wykonawca"
                    s.add(Song(id, readable(c.getString(2)), c.getString(1) ?: "utwór", artist, c.getString(4) ?: "", c.getLong(5),
                        c.getLong(6), c.getString(7) ?: "Inne", ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)))
                }
            }
        } catch (e: Exception) {
        }
        videos = v
        songs = s
        loaded = true
    }

    fun videoFolders(): List<Pair<String, List<Vid>>> = videos.groupBy { it.folder }.toList().sortedByDescending { it.second.maxOf { v -> v.date } }
    fun songFolders(): List<Pair<String, List<Song>>> = songs.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() }
    fun albums(): List<Pair<String, List<Song>>> = songs.groupBy { it.album.ifEmpty { "Bez albumu" } }.toList().sortedBy { it.first.lowercase() }
    fun artists(): List<Pair<String, List<Song>>> = songs.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }

    fun albumArt(albumId: Long): Uri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    /** Miniatura filmu lub okładka utworu (w tle, z pamięcią podręczną). */
    fun thumb(ctx: Context, content: String, w: Int, h: Int, done: (Bitmap?) -> Unit) {
        if (content.isEmpty() || !content.startsWith("content://") || content in missing) {
            done(null); return
        }
        thumbs.get(content)?.let { done(it); return }
        io.execute {
            val bm = try {
                ctx.contentResolver.loadThumbnail(Uri.parse(content), Size(w, h), null)
            } catch (e: Exception) {
                null
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (bm != null) thumbs.put(content, bm) else missing.add(content)
                done(bm)
            }
        }
    }

    /** Kolejka z tego samego folderu (gdy film otwarto z menedżera plików). */
    fun siblings(path: String): List<Track> {
        if (!path.startsWith("/")) return emptyList()
        val dir = File(path).parent ?: return emptyList()
        return videos.filter { it.path.startsWith("$dir/") && File(it.path).parent == dir }.sortedBy { it.title.lowercase() }.map { it.track() }
    }
}
