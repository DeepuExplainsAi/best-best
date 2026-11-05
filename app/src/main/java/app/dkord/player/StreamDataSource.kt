@file:OptIn(UnstableApi::class)

package app.dkord.player

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.dkord.data.stream.AudioQuality
import app.dkord.data.stream.StreamResolver
import kotlinx.coroutines.runBlocking
import java.io.IOException

const val TRACK_SCHEME = "dkord"
fun trackUri(videoId: String) = "$TRACK_SCHEME://track/$videoId"

/** Playback settings as seen by the player; reads the live value from the settings store. */
class StreamConfig(private val source: () -> AudioQuality) {
    val quality: AudioQuality get() = source()
}

/**
 * Turns dkord://track/<id> into a real audio URL at the moment the player opens it, so URLs are
 * always fresh (and a 403 retry simply opens again). Runs on ExoPlayer's loader thread.
 *
 * The cache key is "<id>|<itag>", never the URL: URLs change on every resolve, and bytes of two
 * different formats (opus vs m4a) must never be mixed under one key.
 */
class StreamUriResolver(
    private val streams: StreamResolver,
    private val quality: () -> AudioQuality,
    /** Finished download for this id (file:// or content://), or null. Offline playback uses it. */
    private val localUri: (String) -> String? = { null },
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != TRACK_SCHEME) return dataSpec
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Bad track uri ${dataSpec.uri}")
        localUri(id)?.let { return dataSpec.buildUpon().setUri(it.toUri()).setKey("local|$id").build() }
        val stream = runBlocking { streams.resolve(id, quality()) }
        val itag = ITAG.find(stream.url)?.groupValues?.get(1) ?: stream.mimeType
        val headers = HashMap(dataSpec.httpRequestHeaders)
        stream.userAgent?.let { headers["User-Agent"] = it }
        return dataSpec.buildUpon().setUri(stream.url).setKey("$id|$itag").setHttpRequestHeaders(headers).build()
    }

    private companion object { val ITAG = Regex("[?&]itag=(\\d+)") }
}

/**
 * Sits directly above the HTTP source. googlevideo throttles a request that has no Range header to
 * ~30 KB/s, so an open-ended read from byte 0 gets "bytes=0-". Only that case: for every other
 * position OkHttpDataSource adds its own Range, and two Range headers would break the request.
 */
class OpenRangeResolver : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec =
        if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong())
            dataSpec.buildUpon().setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Range" to "bytes=0-")).build()
        else dataSpec
}

/** Does not retry errors that cannot succeed (age, geo, 403...), so the user sees them immediately. */
class StreamErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (PlaybackErrors.isFatalForLoad(loadErrorInfo.exception)) C.TIME_UNSET
        else super.getRetryDelayMsFor(loadErrorInfo)
}

/** Sends file/content uris (downloads) straight to [local], everything else through the cache chain. */
class SchemeRoutingDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener); remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val s = dataSpec.uri.scheme
        active = if (s == "file" || s == "content") local else remote
        return active!!.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
    override fun getUri() = active?.uri
    override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()
    override fun close() { try { active?.close() } finally { active = null } }

    class Factory(private val local: DataSource.Factory, private val remote: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = SchemeRoutingDataSource(local.createDataSource(), remote.createDataSource())
    }
}
