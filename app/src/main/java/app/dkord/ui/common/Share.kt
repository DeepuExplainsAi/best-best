package app.dkord.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.dkord.R
import app.dkord.core.Track
import app.dkord.core.trackShareText

fun shareTrack(context: Context, track: Track) = sharePlainText(context, trackShareText(track))

fun sharePlainText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
    }
}
