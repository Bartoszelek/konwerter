package pl.konwerter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.service.media.MediaBrowserService

/** Odtwarzanie w tle: powiadomienie z przyciskami, ekran blokady, słuchawki. */
class PlayerService : Service() {
    companion object {
        private const val ID = 21
        private var running = false

        fun start(ctx: Context) {
            if (running) {
                update(ctx); return
            }
            try {
                ctx.startForegroundService(Intent(ctx, PlayerService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, PlayerService::class.java))
        }

        fun update(ctx: Context) {
            if (!running) return
            try {
                ctx.getSystemService(NotificationManager::class.java)?.notify(ID, build(ctx))
            } catch (e: Exception) {
            }
        }

        private fun action(ctx: Context, act: String, code: Int) = PendingIntent.getService(
            ctx, code, Intent(ctx, PlayerService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE
        )

        fun build(ctx: Context): Notification {
            val t = Engine.current
            val open = PendingIntent.getActivity(ctx, 30, Intent(ctx, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val b = Notification.Builder(ctx, "play")
                .setSmallIcon(Icon.createWithResource(ctx, R.drawable.ic_play))
                .setContentTitle(t?.title ?: "Player")
                .setContentText(t?.sub ?: "")
                .setContentIntent(open)
                .setOngoing(Engine.playing)
                .setDeleteIntent(action(ctx, "stop", 34))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_prev), "Poprzedni", action(ctx, "prev", 31)).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, if (Engine.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    if (Engine.playing) "Pauza" else "Odtwórz", action(ctx, "toggle", 32)).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_next), "Następny", action(ctx, "next", 33)).build())
            Engine.art?.let { b.setLargeIcon(it) }
            val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
            Engine.session?.let { style.setMediaSession(it.sessionToken) }
            b.setStyle(style)
            return b.build()
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Engine.pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel("play", "Odtwarzanie", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        running = true
        when (intent?.action) {
            "toggle" -> Engine.toggle()
            "next" -> Engine.next()
            "prev" -> Engine.prev()
            "stop" -> {
                Engine.stop(); return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            unregisterReceiver(noisy)
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Engine.playing) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }
}

/** Android Auto: przeglądanie muzyki (ostatnie, utwory, albumy, wykonawcy, playlisty) i sterowanie. */
class AutoService : MediaBrowserService() {
    companion object {
        private fun lists(ctx: Context): Map<String, List<Track>> {
            if (!Library.loaded) Library.load(ctx)
            val m = LinkedHashMap<String, List<Track>>()
            m["recent"] = Store.history.filter { !it.track.video }.map { it.track }.take(50)
            m["songs"] = Library.songs.map { it.track() }
            Library.albums().forEachIndexed { k, (_, list) -> m["al$k"] = list.map { it.track() } }
            Library.artists().forEachIndexed { k, (_, list) -> m["ar$k"] = list.map { it.track() } }
            Store.playlists.forEachIndexed { k, p -> m["pl$k"] = p.items.filter { !it.video } }
            return m
        }

        /** mediaId = "lista|pozycja". */
        fun play(ctx: Context, mediaId: String) {
            val parts = mediaId.split("|")
            val list = lists(ctx)[parts[0]] ?: return
            Engine.play(list, parts.getOrNull(1)?.toIntOrNull() ?: 0)
        }

        fun search(ctx: Context, q: String) {
            if (!Library.loaded) Library.load(ctx)
            val n = q.lowercase().trim()
            val songs = Library.songs
            val hits = if (n.isEmpty()) songs else songs.filter { n in it.title.lowercase() || n in it.artist.lowercase() || n in it.album.lowercase() }
            if (hits.isNotEmpty()) Engine.play(hits.map { it.track() }, 0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        Engine.session?.sessionToken?.let { sessionToken = it }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserService.BrowserRoot = MediaBrowserService.BrowserRoot("root", null)

    private fun folder(id: String, title: String, sub: String = "") = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
    )

    private fun playable(listId: String, k: Int, t: Track) = MediaBrowser.MediaItem(
        MediaDescription.Builder().setMediaId("$listId|$k").setTitle(t.title).setSubtitle(t.sub)
            .apply { if (t.art.startsWith("content://")) setIconUri(Uri.parse(t.art)) }.build(),
        MediaBrowser.MediaItem.FLAG_PLAYABLE
    )

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        result.detach()
        Thread {
            val out = ArrayList<MediaBrowser.MediaItem>()
            try {
                val all = lists(this)
                when (parentId) {
                    "root" -> {
                        out.add(folder("recent", "Ostatnio słuchane"))
                        out.add(folder("songs", "Wszystkie utwory", "${Library.songs.size}"))
                        out.add(folder("albums", "Albumy"))
                        out.add(folder("artists", "Wykonawcy"))
                        if (Store.playlists.isNotEmpty()) out.add(folder("playlists", "Playlisty"))
                    }
                    "albums" -> Library.albums().forEachIndexed { k, (name, list) -> out.add(folder("al$k", name, list.firstOrNull()?.artist ?: "")) }
                    "artists" -> Library.artists().forEachIndexed { k, (name, list) -> out.add(folder("ar$k", name, "${list.size} utworów")) }
                    "playlists" -> Store.playlists.forEachIndexed { k, p -> out.add(folder("pl$k", p.name, "${p.items.size} pozycji")) }
                    else -> all[parentId]?.take(500)?.forEachIndexed { k, t -> out.add(playable(parentId, k, t)) }
                }
            } catch (e: Exception) {
            }
            result.sendResult(out)
        }.start()
    }
}
