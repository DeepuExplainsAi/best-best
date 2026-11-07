package app.dkord

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import app.dkord.core.DebugLog
import app.dkord.data.net.NewPipeDownloader
import app.dkord.di.appModule
import app.dkord.data.settings.AppLanguages
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class DkordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Interface language must not silently change the music backend's existing device region.
        val l = AppLanguages.deviceLocale(this)
        AppLanguages.initialize(this)
        DebugLog.sink = { Log.println(if (it.level == 'E') Log.ERROR else if (it.level == 'W') Log.WARN else Log.DEBUG, "Dkord/${it.tag}", it.msg) }
        startKoin { androidContext(this@DkordApp); modules(appModule) }
        NewPipe.init(
            NewPipeDownloader(),
            Localization(l.language.ifEmpty { "en" }, l.country.ifEmpty { "US" }),
            ContentCountry(l.country.ifEmpty { "US" }),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguages.initialize(this)
    }
}
