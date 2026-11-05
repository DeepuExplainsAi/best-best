@file:OptIn(UnstableApi::class)

package app.dkord.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.dkord.data.stream.StreamError

/** What went wrong, independent of language. The UI maps this to a localized string. */
enum class PlaybackProblem { BOT_CHECK, AGE_RESTRICTED, KIDS, GEO, PRIVATE, UNAVAILABLE, NETWORK, NO_AUDIO }

data class PlaybackFailure(val videoId: String, val title: String?, val problem: PlaybackProblem, val detail: String?)

object PlaybackErrors {
    fun chain(t: Throwable?): Sequence<Throwable> = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(10)

    fun streamError(t: Throwable?): StreamError? = chain(t).filterIsInstance<StreamError>().firstOrNull()

    fun httpStatus(t: Throwable?): Int? =
        chain(t).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode

    /** googlevideo URLs expire or get bound to a session: 403/410 means "resolve again". */
    fun isUrlExpired(t: Throwable?): Boolean = httpStatus(t)?.let { it == 403 || it == 410 } == true

    /** Errors that retrying the same request cannot fix. */
    fun isFatalForLoad(t: Throwable?): Boolean {
        val se = streamError(t)
        if (se != null) return se !is StreamError.Network
        return httpStatus(t)?.let { it == 403 || it == 404 || it == 410 } == true
    }

    fun isNetwork(e: PlaybackException): Boolean =
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            streamError(e) is StreamError.Network

    fun problemOf(e: StreamError): PlaybackProblem = when (e) {
        is StreamError.BotCheck -> PlaybackProblem.BOT_CHECK
        is StreamError.AgeRestricted -> PlaybackProblem.AGE_RESTRICTED
        is StreamError.MadeForKids -> PlaybackProblem.KIDS
        is StreamError.Geo -> PlaybackProblem.GEO
        is StreamError.Private -> PlaybackProblem.PRIVATE
        is StreamError.Unavailable -> PlaybackProblem.UNAVAILABLE
        is StreamError.Network -> PlaybackProblem.NETWORK
        is StreamError.NoAudio -> PlaybackProblem.NO_AUDIO
    }
}
