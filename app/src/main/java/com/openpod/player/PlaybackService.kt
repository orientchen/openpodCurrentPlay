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
