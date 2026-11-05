package app.dkord

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class DkordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { androidContext(this@DkordApp) }
    }
}
