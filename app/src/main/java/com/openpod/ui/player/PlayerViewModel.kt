package com.openpod.ui.player

import androidx.lifecycle.ViewModel
import com.openpod.player.PlayerController
import com.openpod.player.PlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController
) : ViewModel() {
    val state: StateFlow<PlayerState> = playerController.state
    fun nextEpisode() = playerController.playAdjacentEpisode(next = true)
    fun previousEpisode() = playerController.playAdjacentEpisode(next = false)
    fun playPause() = playerController.playPause()
    fun seekForward() = playerController.seekForward()
    fun seekBack() = playerController.seekBack()
    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)
}
