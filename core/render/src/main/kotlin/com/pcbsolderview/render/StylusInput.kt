package com.pcbsolderview.render

import android.view.MotionEvent

/**
 * S Pen side-button state, read from the raw Android events by the Activity (touch and hover).
 * Compose does not expose BUTTON_STYLUS_PRIMARY directly, so the Activity forwards every event here.
 */
object StylusInput {
    @Volatile
    var buttonPressed: Boolean = false
        private set

    /** Called on the main thread when the button state changes. */
    var listener: ((Boolean) -> Unit)? = null

    fun onMotionEvent(ev: MotionEvent) {
        if (ev.pointerCount == 0) return
        val tool = ev.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        val pressed = tool == MotionEvent.TOOL_TYPE_ERASER ||
            (ev.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0
        if (pressed != buttonPressed) {
            buttonPressed = pressed
            listener?.invoke(pressed)
        }
    }
}
