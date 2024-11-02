package dev.anilbeesetti.nextplayer.feature.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.anilbeesetti.nextplayer.core.common.extensions.deleteFiles
import dev.anilbeesetti.nextplayer.core.common.extensions.getFilenameFromUri
import dev.anilbeesetti.nextplayer.core.common.extensions.subtitleCacheDir
import dev.anilbeesetti.nextplayer.core.data.models.VideoState
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.DecoderPriority
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Resume
import dev.anilbeesetti.nextplayer.feature.player.PlayerActivity
import dev.anilbeesetti.nextplayer.feature.player.R
import dev.anilbeesetti.nextplayer.feature.player.extensions.addAdditionSubtitleConfigurations
import dev.anilbeesetti.nextplayer.feature.player.extensions.getCurrentTrackIndex
import dev.anilbeesetti.nextplayer.feature.player.extensions.getLocalSubtitles
import dev.anilbeesetti.nextplayer.feature.player.extensions.switchTrack
import dev.anilbeesetti.nextplayer.feature.player.extensions.toSubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.extensions.updateSubtitleConfigurations
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaSessionService() {
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var mediaRepository: MediaRepository

    private val playerPreferences: PlayerPreferences
        get() = runBlocking { preferencesRepository.playerPreferences.first() }

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false
    private var currentMediaItem = MutableStateFlow<MediaItem?>(null)
    private var currentVideoState: VideoState? = null

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentMediaItem.update { mediaItem }
            isMediaItemReady = false
            if (mediaItem != null) {
                currentVideoState = runBlocking { mediaRepository.getVideoState(mediaItem.mediaId) }
                if (playerPreferences.resume == Resume.YES) {
                    currentVideoState?.position?.let { mediaSession?.player?.seekTo(it) }
                }
            }
            super.onMediaItemTransition(mediaItem, reason)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (
                (reason == DISCONTINUITY_REASON_SEEK || reason == DISCONTINUITY_REASON_AUTO_TRANSITION) &&
                oldPosition.mediaItem != null &&
                newPosition.mediaItem != null &&
                oldPosition.mediaItem != newPosition.mediaItem
            ) {
                mediaRepository.updateMediumPosition(
                    uri = oldPosition.mediaItem!!.mediaId,
                    position = if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) C.TIME_UNSET else oldPosition.positionMs,
                )
            }

            if (reason == DISCONTINUITY_REASON_REMOVE && oldPosition.mediaItem != null) {
                mediaRepository.updateMediumPosition(
                    uri = oldPosition.mediaItem!!.mediaId,
                    position = oldPosition.positionMs,
                )
            }
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            mediaRepository.updateMediumPlaybackSpeed(
                uri = mediaSession?.player?.currentMediaItem?.mediaId ?: return,
                playbackSpeed = playbackParameters.speed,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            mediaSession?.player?.getCurrentTrackIndex(C.TRACK_TYPE_AUDIO)?.let { audioTrackIndex ->
                mediaRepository.updateMediumAudioTrack(
                    uri = mediaSession?.player?.currentMediaItem?.mediaId ?: return@let,
                    audioTrackIndex = audioTrackIndex,
                )
            }

            mediaSession?.player?.getCurrentTrackIndex(C.TRACK_TYPE_TEXT)?.let { subtitleTrackIndex ->
                mediaRepository.updateMediumSubtitleTrack(
                    uri = mediaSession?.player?.currentMediaItem?.mediaId ?: return@let,
                    subtitleTrackIndex = subtitleTrackIndex,
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            when (playbackState) {
                Player.STATE_READY -> {
                    if (!isMediaItemReady) {
                        currentVideoState?.let { state ->
                            mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, state.audioTrackIndex)
                            mediaSession?.player?.switchTrack(C.TRACK_TYPE_TEXT, state.subtitleTrackIndex)
                            state.playbackSpeed?.let { mediaSession?.player?.setPlaybackSpeed(it) }
                        }
                        isMediaItemReady = true
                    }
                }

                else -> {}
            }
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)
                        ?.let { Uri.parse(it) }
                        ?: run {
                            return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                        }

                    val newSubConfiguration = subtitleUri.toSubtitleConfiguration(
                        context = this@PlayerService,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    // TODO: add new subtitle uri to media state
                    mediaSession?.player?.addAdditionSubtitleConfigurations(listOf(newSubConfiguration))
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onCreate() {
        super.onCreate()
        val renderersFactory = NextRenderersFactory(applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                when (playerPreferences.decoderPriority) {
                    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )

        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also {
                it.addListener(playbackStateListener)
            }

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player!!
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        subtitleCacheDir.deleteFiles()
        mediaSession?.run {
            player.removeListener(playbackStateListener)
            player.release()
            release()
            mediaSession = null
        }
    }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val mediaState = mediaRepository.getVideoState(uri = mediaItem.mediaId)

                val uri = Uri.parse(mediaItem.mediaId)
                val externalSubs = mediaState?.externalSubs ?: emptyList()
                val localSubs = uri.getLocalSubtitles(context = this@PlayerService, excludeSubsList = externalSubs)

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val subConfigurations = (externalSubs + localSubs).map { subtitleUri ->
                    subtitleUri.toSubtitleConfiguration(
                        context = this@PlayerService,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                }
                val title = mediaItem.mediaMetadata.title ?: mediaState?.title ?: getFilenameFromUri(uri)
                val artwork = mediaState?.thumbnailPath?.let { Uri.parse(it) } ?: Uri.Builder().apply {
                    val defaultArtwork = R.drawable.artwork_default
                    scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    authority(resources.getResourcePackageName(defaultArtwork))
                    appendPath(resources.getResourceTypeName(defaultArtwork))
                    appendPath(resources.getResourceEntryName(defaultArtwork))
                }.build()

                mediaItem.buildUpon().apply {
                    setUri(mediaItem.mediaId)
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artwork)
                        }.build(),
                    )
                }.build()
            }
        }.awaitAll()
    }
}