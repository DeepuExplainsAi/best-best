package app.dkord.di

import app.dkord.data.auth.AuthStore
import app.dkord.data.auth.CookieSource
import app.dkord.data.stream.StreamModule
import app.dkord.data.stream.StreamResolver
import app.dkord.data.stream.VisitorData
import app.dkord.data.yt.InnerTubeClient
import app.dkord.data.yt.MusicRepository
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single { AuthStore(androidContext()) } bind CookieSource::class
    single { VisitorData() }
    single { StreamModule(get()) } bind StreamResolver::class
    single { InnerTubeClient(get<AuthStore>()) }
    single { MusicRepository(get()) }
}
