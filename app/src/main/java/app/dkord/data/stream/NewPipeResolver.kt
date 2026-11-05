package app.dkord.data.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException

/** Fallback 1: the extractor's default clients. */
class NewPipeResolver : StreamResolver {
    override val name = "newpipe"

    override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean): ResolvedStream =
        withContext(Dispatchers.IO) {
            try {
                val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                ex.fetchPage()
                val streams = ex.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
                val picked = AudioPicker.pick(
                    streams.map {
                        AudioCandidate(
                            url = it.content,
                            mimeType = it.format?.mimeType?.let { m -> "$m; codecs=\"${it.codec}\"" } ?: "audio/unknown",
                            bitrate = if (it.bitrate > 0) it.bitrate else it.averageBitrate * 1000,
                            isOriginalTrack = it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL,
                        )
                    },
                    quality, preferM4a,
                ) ?: throw StreamError.NoAudio()
                ResolvedStream(videoId, picked.url, picked.mimeType, picked.bitrate, null, expiryFromUrl(picked.url), null, name)
            } catch (e: StreamError) { throw e
            } catch (e: SignInConfirmNotBotException) { throw StreamError.BotCheck()
            } catch (e: ReCaptchaException) { throw StreamError.BotCheck("(rate limited)")
            } catch (e: AgeRestrictedContentException) { throw StreamError.AgeRestricted()
            } catch (e: PrivateContentException) { throw StreamError.Private()
            } catch (e: GeographicRestrictionException) { throw StreamError.Geo()
            } catch (e: ContentNotAvailableException) { throw StreamError.Unavailable(e.message.orEmpty())
            } catch (e: IOException) { throw StreamError.Network(e)
            } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                throw StreamError.Unavailable("Extractor failed: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }
}
