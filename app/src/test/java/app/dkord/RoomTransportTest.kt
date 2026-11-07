package app.dkord

import app.dkord.data.room.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RoomTransportTest {
    private val msg = RoomMessage("abcdefghijk", "Song", "Artist", positionMs = 3_000, playing = true, timestamp = 1_800_000_000_000)
    private fun line(id: String) = buildJsonObject {
        put("event", "message"); put("id", id); put("message", RoomCodec.encode(msg))
    }.toString() + "\n"

    private fun client(code: Int = 200, body: String, capture: (Request) -> Unit) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); capture(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun poll_uses_12_hours_and_live_resumes_after_last_cached_id() = runBlocking {
        val requests = mutableListOf<Request>()
        val transport = NtfyRoomTransport(client(body = line("older") + line("newer")) { requests += it })
        val cached = transport.latest("ABCDEFGHJK")
        assertEquals("newer", cached?.cursor)
        assertEquals("12h", requests[0].url.queryParameter("since"))
        assertEquals("1", requests[0].url.queryParameter("poll"))
        assertEquals("older", transport.subscribe("ABCDEFGHJK", cached?.cursor).first().cursor)
        assertEquals("newer", requests[1].url.queryParameter("since"))
        assertNull(requests[1].url.queryParameter("poll"))
    }

    @Test fun publish_contains_only_bounded_room_json_and_no_session_headers() = runBlocking {
        var captured: Request? = null
        NtfyRoomTransport(client(body = "{}") { captured = it }).publish("ABCDEFGHJK", msg)
        val request = checkNotNull(captured)
        assertEquals("/dkord-ABCDEFGHJK", request.url.encodedPath)
        assertEquals("POST", request.method)
        assertNull(request.header("Cookie"))
        assertNull(request.header("Authorization"))
        val buffer = Buffer(); checkNotNull(request.body).writeTo(buffer)
        val body = buffer.readUtf8()
        assertTrue(body.toByteArray(Charsets.UTF_8).size < 4000)
        assertEquals(msg, RoomCodec.decode(body))
    }

    @Test fun rate_limit_is_a_handled_transport_error() = runBlocking {
        try {
            NtfyRoomTransport(client(429, "Too many requests") {}).latest("ABCDEFGHJK")
            fail("Expected rate limit")
        } catch (e: RoomTransportException) { assertTrue(e.rateLimited) }
    }
}
