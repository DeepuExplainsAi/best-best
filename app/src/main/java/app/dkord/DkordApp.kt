package app.dkord

import android.app.Application
import android.util.Log
import app.dkord.core.DebugLog
import app.dkord.data.net.NewPipeDownloader
import app.dkord.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class DkordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.sink = { Log.println(if (it.level == 'E') Log.ERROR else if (it.level == 'W') Log.WARN else Log.DEBUG, "Dkord/${it.tag}", it.msg) }
        startKoin { androidContext(this@DkordApp); modules(appModule) }
        val l = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(),
            Localization(l.language.ifEmpty { "en" }, l.country.ifEmpty { "US" }),
            ContentCountry(l.country.ifEmpty { "US" }),
        )
    }
}
