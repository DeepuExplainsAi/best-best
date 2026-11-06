package app.dkord.di

import app.dkord.data.auth.AuthStore
import app.dkord.data.db.AppDatabase
import app.dkord.data.download.DownloadManager
import app.dkord.data.download.DownloadStorage
import app.dkord.data.library.LibraryRepository
import app.dkord.data.lyrics.LyricsRepository
import app.dkord.ui.common.Actions
import app.dkord.data.settings.SettingsRepository
import app.dkord.data.auth.CookieSource
import app.dkord.data.stream.StreamModule
import app.dkord.data.stream.StreamResolver
import app.dkord.data.stream.VisitorData
import app.dkord.data.yt.InnerTubeClient
import app.dkord.data.yt.MusicRepository
import app.dkord.player.AudioCache
import app.dkord.player.PlayerController
import app.dkord.player.QueueStore
import app.dkord.player.SleepTimer
import app.dkord.player.StreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single { AuthStore(androidContext()) } bind CookieSource::class
    single { VisitorData() }
    single { StreamModule(get()) } bind StreamResolver::class
    single { InnerTubeClient(get<AuthStore>()) }
    single { MusicRepository(get()) }
    single { SettingsRepository(androidContext()) }
    single { AudioCache(androidContext(), get<SettingsRepository>().settings.value.cacheMb * 1024L * 1024L) }
    single { StreamConfig { get<SettingsRepository>().settings.value.quality } }
    single { AppDatabase.build(androidContext()) }
    single { LibraryRepository(get()) }
    single { DownloadStorage(androidContext()) }
    single { DownloadManager(androidContext(), get(), get()) }
    single { QueueStore(androidContext()) }
    single { SleepTimer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    single { PlayerController(androidContext(), get()) }
    single { LyricsRepository() }
    single { Actions(get(), get(), get()) }
    single { app.dkord.data.update.AppUpdater(androidContext()) }
}
