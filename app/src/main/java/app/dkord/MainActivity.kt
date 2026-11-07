package app.dkord

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import app.dkord.data.settings.AppLanguages
import app.dkord.data.settings.SettingsRepository
import app.dkord.player.PlayerController
import app.dkord.ui.App
import app.dkord.ui.theme.DkordTheme
import org.koin.android.ext.android.inject

class MainActivity : AppCompatActivity() {
    private val player: PlayerController by inject()
    private val settings: SettingsRepository by inject()

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: Context) {
        AppLanguages.initialize(newBase)
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player.connect()
        // Android 13+: without this the media notification and lock-screen controls stay hidden.
        // Asked once per launch, only while still denied; playback itself works without it.
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val s by settings.settings.collectAsState()
            DkordTheme(s.theme, s.dynamicColor) { App() }
        }
    }
}
