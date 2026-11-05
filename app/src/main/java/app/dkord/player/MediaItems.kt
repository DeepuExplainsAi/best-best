package app.dkord.player

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.dkord.core.Track

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(trackUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(thumbnail?.toUri())
            .setDurationMs(durationSec.takeIf { it > 0 }?.times(1000))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()

fun MediaItem.toTrack(): Track = Track(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString(),
    durationSec = (mediaMetadata.durationMs ?: 0L) / 1000,
    thumbnail = mediaMetadata.artworkUri?.toString(),
)

