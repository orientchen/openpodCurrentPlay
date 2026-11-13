package com.openpod.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.openpod.data.db.EpisodeDao
import com.openpod.data.db.EpisodeWithPodcast
import com.openpod.data.db.PodcastDao
import com.openpod.data.repository.PodcastRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var episodeDao: EpisodeDao
    @Inject lateinit var podcastDao: PodcastDao
    @Inject lateinit var podcastRepository: PodcastRepository

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null
    private var currentIsLocal = false
    private var changingEpisode = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                saveJob = scope.launch {
                    while (true) {
                        delay(5_000)
                        val guid = player.currentMediaItem?.mediaId ?: continue
                        episodeDao.updateProgress(guid, player.currentPosition, false, System.currentTimeMillis())
                    }
                }
            } else {
                saveJob?.cancel()
                // Save immediately on pause/stop so the position isn't lost
                // between the last periodic save and when playback stopped.
                val guid = player.currentMediaItem?.mediaId ?: return
                scope.launch {
                    episodeDao.updateProgress(guid, player.currentPosition, false, System.currentTimeMillis())
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            mediaLibrarySession.notifyChildrenChanged("root", Int.MAX_VALUE, null)
            val guid = mediaItem?.mediaId ?: return
            scope.launch {
                val ep = episodeDao.getByGuid(guid)
                currentIsLocal = ep?.localFilePath != null
                val savedPos = ep?.playPositionMs ?: 0L
                if (savedPos > 0 && player.currentPosition < 1_000L) {
                    player.seekTo(savedPos)
                }
                updateSubtitle(if (currentIsLocal) "Local file" else "Streaming")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (!currentIsLocal) updateSubtitle("Buffering…")
                Player.STATE_READY -> updateSubtitle(if (currentIsLocal) "Local file" else "Streaming")
                Player.STATE_ENDED -> scope.launch {
                    val guid = player.currentMediaItem?.mediaId ?: return@launch
                    episodeDao.updateProgress(guid, 0L, true, System.currentTimeMillis())
                    // Use the same established Next ordering when playback ends naturally.
                    playAdjacentEpisode(next = true, currentAlreadyCompleted = true)
                }
            }
        }
    }

    private fun updateSubtitle(subtitle: String) {
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.subtitle?.toString() == subtitle) return
        player.replaceMediaItem(
            player.currentMediaItemIndex,
            item.buildUpon()
                .setMediaMetadata(item.mediaMetadata.buildUpon().setSubtitle(subtitle).build())
                .build()
        )
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            scope.launch {
                podcastRepository.refreshAll()
                mediaLibrarySession.notifyChildrenChanged("history", Int.MAX_VALUE, null)
                mediaLibrarySession.notifyChildrenChanged("downloads", Int.MAX_VALUE, null)
                mediaLibrarySession.notifyChildrenChanged("podcasts", Int.MAX_VALUE, null)
            }
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle("OpenPod")
                    .build())
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val items: List<MediaItem> = when {
                    parentId == "root" -> {
                        val roots = mutableListOf<MediaItem>()
                        val currentGuid = player.currentMediaItem?.mediaId
                        if (currentGuid != null) {
                            val current = episodeDao.getByGuid(currentGuid)
                            if (current != null) {
                                roots.add(browsableItem("around-current", "Around Current Episode"))
                            }
                        }
                        roots.add(browsableItem("podcasts", "Podcasts"))
                        roots.add(browsableItem("history", "History"))
                        roots.add(browsableItem("downloads", "Downloads"))
                        roots
                    }
                    parentId == "around-current" -> {
                        val currentGuid = player.currentMediaItem?.mediaId
                        val current = currentGuid?.let { episodeDao.getByGuid(it) }
                        if (current == null) {
                            emptyList()
                        } else {
                            val episodes = episodeDao.getForPodcastOnce(current.podcastFeedUrl)
                            val index = episodes.indexOfFirst { it.guid == current.guid }
                            if (index < 0) {
                                emptyList()
                            } else {
                                val from = (index - 5).coerceAtLeast(0)
                                val to = (index + 6).coerceAtMost(episodes.size)
                                episodes.subList(from, to).map { episode ->
                                    val item = episode.toMediaItem()
                                    if (episode.guid == current.guid) {
                                        item.buildUpon().setMediaMetadata(
                                            item.mediaMetadata.buildUpon()
                                                .setTitle("▶ Playing: ${episode.title}")
                                                .build()
                                        ).build()
                                    } else item
                                }
                            }
                        }
                    }
                    parentId == "podcasts" -> episodeDao.getPodcastFeedUrlsOnce().mapNotNull { feedUrl ->
                        podcastDao.getByFeedUrl(feedUrl)?.let { podcast ->
                            browsableItem("podcast:$feedUrl", podcast.title)
                        }
                    }
                    parentId.startsWith("podcast:") -> {
                        val feedUrl = parentId.removePrefix("podcast:")
                        episodeDao.getForPodcastOnce(feedUrl).map { it.toMediaItem() }
                    }
                    parentId == "history" -> episodeDao.getPlayHistory().first().map { it.toMediaItem() }
                    parentId == "downloads" -> episodeDao.getCompletedDownloadsOnce().map { it.toMediaItem() }
                    else -> emptyList()
                }
                val safePage = page.coerceAtLeast(0)
                val safeSize = pageSize.coerceAtLeast(1)
                val from = (safePage.toLong() * safeSize).coerceAtMost(items.size.toLong()).toInt()
                val to = (from.toLong() + safeSize).coerceAtMost(items.size.toLong()).toInt()
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, to)), params))
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaItemsWithStartPosition>()
            scope.launch {
                val resolved = mediaItems.map { item ->
                    val ep = episodeDao.getByGuid(item.mediaId)
                    if (ep != null) {
                        MediaItem.Builder()
                            .setMediaId(ep.guid)
                            .setUri(ep.localFilePath ?: ep.audioUrl)
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setTitle(ep.title)
                                .build())
                            .build()
                    } else item
                }
                future.set(MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val ep = episodeDao.getByGuid(mediaId)
                if (ep != null) {
                    future.set(LibraryResult.ofItem(
                        MediaItem.Builder()
                            .setMediaId(ep.guid)
                            .setUri(ep.audioUrl)
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setTitle(ep.title)
                                .build())
                            .build(),
                        null
                    ))
                } else {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            }
            return future
        }
    }

    // Same chronological rule as the phone app: newest first in the database.
    // Next = older (index + 1); Previous = newer (index - 1).
    private fun playAdjacentEpisode(next: Boolean, currentAlreadyCompleted: Boolean = false) {
        if (changingEpisode) return
        val guid = player.currentMediaItem?.mediaId ?: return
        changingEpisode = true
        scope.launch {
            try {
                val current = episodeDao.getByGuid(guid) ?: return@launch
                val episodes = episodeDao.getForPodcastOnce(current.podcastFeedUrl)
                val index = episodes.indexOfFirst { it.guid == guid }
                if (index < 0) return@launch
                val target = episodes.getOrNull(index + if (next) 1 else -1) ?: return@launch
                val artwork = podcastDao.getByFeedUrl(target.podcastFeedUrl)?.artworkUrl
                val item = target.toMediaItem(artwork)
                // Save current progress before replacing the single media item.
                if (!currentAlreadyCompleted) {
                    episodeDao.updateProgress(guid, player.currentPosition, false, System.currentTimeMillis())
                }
                player.setMediaItem(item, target.playPositionMs.coerceAtLeast(0L))
                player.prepare()
                player.play()
            } finally {
                changingEpisode = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekForwardIncrementMs(30_000)
            .setSeekBackIncrementMs(30_000)
            .build()
            .also { it.addListener(playerListener) }
        val seekingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int) =
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                super.isCommandAvailable(command)

            override fun seekToNextMediaItem() { playAdjacentEpisode(next = true) }
            override fun seekToNext() { playAdjacentEpisode(next = true) }
            override fun seekToPreviousMediaItem() { playAdjacentEpisode(next = false) }
            override fun seekToPrevious() { playAdjacentEpisode(next = false) }
        }
        mediaLibrarySession = MediaLibrarySession.Builder(this, seekingPlayer, libraryCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        mediaLibrarySession

    override fun onDestroy() {
        saveJob?.cancel()
        mediaLibrarySession.release()
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }
}

private fun EpisodeWithPodcast.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(episode.guid)
        .setUri(episode.localFilePath ?: episode.audioUrl)
        .setMediaMetadata(MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setTitle(episode.title)
            .setArtist(podcastTitle)
            .build())
        .build()

private fun browsableItem(id: String, title: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder()
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setTitle(title)
            .build())
        .build()

private fun com.openpod.data.db.Episode.toMediaItem(artworkUrl: String? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(guid)
        .setUri(localFilePath ?: audioUrl)
        .setMediaMetadata(MediaMetadata.Builder()
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setTitle(title)
            .setArtworkUri(artworkUrl?.let { android.net.Uri.parse(it) })
            .build())
        .build()
