package app.dkord.data.room

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** No account, audio transport, or server-specific details leak into room coordination/UI. */
interface RoomTransport {
    suspend fun publish(code: String, message: RoomMessage)
    suspend fun latest(code: String): RoomEvent?
    fun subscribe(code: String, after: String? = null): Flow<RoomEvent>
}

class RoomTransportException(val rateLimited: Boolean, message: String) : IOException(message)
