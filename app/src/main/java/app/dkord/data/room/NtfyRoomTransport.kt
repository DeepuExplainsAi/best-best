package app.dkord.data.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Public relay, without end-to-end encryption: anyone with the code can read/publish. */
class NtfyRoomTransport(
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) : RoomTransport {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).build()
    private val streaming = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
    private val base = "https://ntfy.sh/".toHttpUrl()

    override suspend fun publish(code: String, message: RoomMessage) = withContext(Dispatchers.IO) {
        val body = RoomCodec.encode(message).toRequestBody("text/plain; charset=utf-8".toMediaType())
        execute(http, Request.Builder().url(topicUrl(code)).post(body).build()) { Unit }
    }

    override suspend fun latest(code: String): RoomEvent? = withContext(Dispatchers.IO) {
        val url = topicUrl(code).newBuilder().addPathSegment("json")
            .addQueryParameter("poll", "1").addQueryParameter("since", "12h").build()
        execute(http, Request.Builder().url(url).build()) { response ->
            var last: RoomEvent? = null
            val source = response.body.source()
            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()
                RoomCodec.decodeRelayLine(source.readUtf8LineStrict(16_384))?.let { last = it }
            }
            last
        }
    }

    override fun subscribe(code: String, after: String?): Flow<RoomEvent> = flow {
        // Resume after the polled relay id: cached state first, with no poll/subscribe race.
        val url = topicUrl(code).newBuilder().addPathSegment("json")
            .addQueryParameter("since", after ?: "12h").build()
        execute(streaming, Request.Builder().url(url).build()) { response ->
            val source = response.body.source()
            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()
                RoomCodec.decodeRelayLine(source.readUtf8LineStrict(16_384))?.let { emit(it) }
            }
            throw RoomTransportException(false, "Room relay stream disconnected")
        }
    }.flowOn(Dispatchers.IO)

    private fun topicUrl(code: String) = base.newBuilder().addPathSegment(RoomCode.topic(code)).build()

    private suspend fun <T> execute(client: OkHttpClient, request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        // Cancels a blocking read as well as the initial HTTP call when the room is left.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code == 429) throw RoomTransportException(true, "Room relay rate limited")
                if (!response.isSuccessful) throw RoomTransportException(false, "Room relay HTTP ${response.code}")
                block(response)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: RoomTransportException) { throw e }
        catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw RoomTransportException(false, "Room relay connection failed")
        }
        finally { cancellation.cancel() }
    }
}
