package com.pcbsolderview

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import android.view.MotionEvent
import com.pcbsolderview.render.StylusInput
import com.pcbsolderview.ui.PcbTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcbsolderview.ui.BoardScreen

class MainActivity : ComponentActivity() {
    private val vm: BoardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.open(it) }
            PcbTheme {
                BoardScreen(state = state, vm = vm, openFiles = { picker.launch(arrayOf("*/*")) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // S Pen side button: held = eraser, released = pen.
        StylusInput.listener = { pressed -> vm.onStylusButton(pressed) }
    }

    override fun onPause() {
        StylusInput.listener = null
        super.onPause()
    }

    /** Raw touch events carry the S Pen button state that Compose doesn't expose. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        StylusInput.onMotionEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Hover events (pen near the screen, not touching). */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        StylusInput.onMotionEvent(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with…" (VIEW) and share (SEND / SEND_MULTIPLE). */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()
            else -> emptyList()
        }
        vm.open(uris)
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streamExtras(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
