package app.dkord.data.stream

/** Wiring of the fallback order: extractor default -> alternate player client -> retry with fresh visitor data. */
class StreamModule(visitor: VisitorData = VisitorData()) : StreamResolver {
    private val cached = CachedStreamResolver(
        FallbackStreamResolver(
            listOf(
                NewPipeResolver(),
                InnerTubeResolver(ClientProfile.ANDROID_VR, visitor),
                InnerTubeResolver(ClientProfile.IOS, visitor),
                InnerTubeResolver(ClientProfile.ANDROID_VR, visitor, useFreshVisitor = true),
                InnerTubeResolver(ClientProfile.IOS, visitor, useFreshVisitor = true),
            ),
        ),
    )
    override val name = "stream-module"
    override suspend fun resolve(videoId: String, quality: AudioQuality, preferM4a: Boolean) =
        cached.resolve(videoId, quality, preferM4a)

    /** Call when playback fails with 403: the cached URL is dead. */
    fun invalidate(videoId: String) = cached.invalidate(videoId)
    fun clearCache() = cached.clear()
}
