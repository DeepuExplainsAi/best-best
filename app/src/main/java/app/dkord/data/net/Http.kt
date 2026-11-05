package app.dkord.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val JSON = "application/json; charset=utf-8".toMediaType()
}

/** Bridges NewPipeExtractor to OkHttp. */
class NewPipeDownloader(private val http: OkHttpClient = Http.client) : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val b = Request.Builder().url(request.url())
        request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { b.addHeader(k, it) } }
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        b.method(request.httpMethod(), body)
        http.newCall(b.build()).execute().use { res ->
            if (res.code == 429) throw ReCaptchaException("rate limited (429)", request.url())
            return Response(res.code, res.message, res.headers.toMultimap(), res.body.string(), res.request.url.toString())
        }
    }
}
