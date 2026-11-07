package app.dkord

import app.dkord.data.yt.MusicParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class MusicRadioParserTest {
    @Test
    fun keeps_music_in_radio_order() {
        val songs = MusicParser.parseWatchNext(panel(
            row("second", "MUSIC_VIDEO_TYPE_OMV"),
            row("first"),
        ))
        check(songs.map { it.id } == listOf("second", "first"))
        check(songs.first().artist == "Artist")
        check(songs.first().durationSec == 184L)
        check(songs.first().thumbnail == "https://images.test/song.jpg")
    }

    @Test
    fun reads_wrapped_primary_music_row_only() {
        val wrapped = buildJsonObject {
            put("playlistPanelVideoWrapperRenderer", buildJsonObject {
                put("primaryRenderer", row("primary"))
                put("counterpart", JsonArray(listOf(row("counterpart"))))
            })
        }
        check(MusicParser.parseWatchNext(panel(wrapped)).map { it.id } == listOf("primary"))
    }

    @Test
    fun rejects_user_videos_podcasts_and_unclassified_rows() {
        val songs = MusicParser.parseWatchNext(panel(
            row("vlog", "MUSIC_VIDEO_TYPE_UGC"),
            row("podcast", "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"),
            row("unknown", "FUTURE_VIDEO_TYPE"),
            row("unclassified", null),
            row("song"),
        ))
        check(songs.map { it.id } == listOf("song"))
    }

    @Test
    fun rejects_unavailable_music() {
        val songs = MusicParser.parseWatchNext(panel(
            row("unavailable", unavailable = true),
            row("not-playable", playable = false),
            row("playable"),
        ))
        check(songs.map { it.id } == listOf("playable"))
    }

    @Test
    fun rejects_blank_ids_titles_and_non_song_renderers() {
        val unrelated = buildJsonObject { put("automixPreviewVideoRenderer", buildJsonObject {}) }
        val songs = MusicParser.parseWatchNext(panel(
            row(" "),
            row("blank-title", title = " "),
            unrelated,
            row("song"),
        ))
        check(songs.map { it.id } == listOf("song"))
    }

    @Test
    fun reads_simple_text_title_and_duration() {
        val songs = MusicParser.parseWatchNext(panel(row("song", simpleText = true)))
        check(songs.single().title == "Song")
        check(songs.single().durationSec == 184L)
    }

    @Test
    fun keeps_unknown_duration_for_repository_policy() {
        val song = MusicParser.parseWatchNext(panel(row("song", duration = ""))).single()
        check(song.durationSec == 0L)
    }

    @Test
    fun missing_queue_is_empty() {
        check(MusicParser.parseWatchNext(buildJsonObject {}).isEmpty())
    }

    @Test
    fun does_not_accept_an_audio_counterpart_of_a_non_music_primary_row() {
        val wrapped = buildJsonObject {
            put("playlistPanelVideoWrapperRenderer", buildJsonObject {
                put("primaryRenderer", row("vlog", "MUSIC_VIDEO_TYPE_UGC"))
                put("counterpart", JsonArray(listOf(row("audio"))))
            })
        }
        check(MusicParser.parseWatchNext(panel(wrapped)).isEmpty())
    }

    @Test
    fun existing_browse_fixtures_still_parse() {
        fun fixture(name: String) = app.dkord.data.yt.lenientJson.parseToJsonElement(
            javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())
        check(MusicParser.parsePage(fixture("home")).shelves.isNotEmpty())
        val playlist = MusicParser.parsePage(fixture("playlist"))
        check(playlist.tracks.size == 4)
        check(playlist.tracks.first().title == "Sapphire")
        check(playlist.tracks.first().durationSec == 184L)
        check(playlist.continuation != null)
    }

    companion object {
        internal fun panel(vararg rows: JsonElement): JsonObject = buildJsonObject {
            put("contents", buildJsonObject {
                put("musicQueueRenderer", buildJsonObject {
                    put("content", buildJsonObject {
                        put("playlistPanelRenderer", buildJsonObject {
                            put("contents", JsonArray(rows.toList()))
                        })
                    })
                })
            })
        }

        internal fun row(
            id: String,
            videoType: String? = "MUSIC_VIDEO_TYPE_ATV",
            title: String = "Song",
            duration: String = "3:04",
            unavailable: Boolean = false,
            playable: Boolean = true,
            simpleText: Boolean = false,
        ): JsonObject = buildJsonObject {
            put("playlistPanelVideoRenderer", buildJsonObject {
                put("videoId", id)
                put("title", text(title, simpleText))
                put("longBylineText", text("Artist"))
                put("lengthText", text(duration, simpleText))
                put("isPlayable", playable)
                if (unavailable) put("unplayableText", text("Unavailable"))
                put("thumbnail", buildJsonObject {
                    put("thumbnails", JsonArray(listOf(buildJsonObject {
                        put("url", "//images.test/song.jpg")
                    })))
                })
                if (videoType != null) put("navigationEndpoint", buildJsonObject {
                    put("watchEndpoint", buildJsonObject {
                        put("watchEndpointMusicSupportedConfigs", buildJsonObject {
                            put("watchEndpointMusicConfig", buildJsonObject {
                                put("musicVideoType", videoType)
                            })
                        })
                    })
                })
            })
        }

        private fun text(value: String, simple: Boolean = false): JsonObject = buildJsonObject {
            if (simple) put("simpleText", value)
            else put("runs", JsonArray(listOf(buildJsonObject { put("text", value) })))
        }
    }
}
