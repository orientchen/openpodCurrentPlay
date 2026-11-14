package com.openpod.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openpod.data.db.Episode
import com.openpod.data.db.EpisodeWithPodcast
import com.openpod.data.download.DownloadRepository
import com.openpod.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PlayHistoryViewModel @Inject constructor(
    private val repository: PodcastRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {
    val episodes: StateFlow<List<EpisodeWithPodcast>> = repository.getPlayHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeFromHistory(episode: Episode) {
        viewModelScope.launch { repository.removeFromHistory(episode.guid) }
    }

    fun download(episode: Episode) = downloadRepository.enqueue(episode)
    fun cancelDownload(episode: Episode) = downloadRepository.cancel(episode)
}
