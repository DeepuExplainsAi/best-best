package app.dkord.di

import app.dkord.data.auth.AuthStore
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
    single { AudioCache(androidContext()) }
    single { StreamConfig() }
    single { QueueStore(androidContext()) }
    single { SleepTimer(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) }
    single { PlayerController(androidContext(), get()) }
}
