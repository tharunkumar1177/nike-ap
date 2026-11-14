// PadSession.kt: wires one touch surface to the mouse and pen pipelines and the transport router.
package dev.padlink.ui

import android.content.Context
import dev.padlink.input.GestureConfig
import dev.padlink.input.GestureEngine
import dev.padlink.input.MainThreadScheduler
import dev.padlink.input.MouseController
import dev.padlink.input.PenController
import dev.padlink.input.StylusInput
import dev.padlink.input.TouchRouter
import dev.padlink.settings.Settings
import dev.padlink.transport.HostSize
import dev.padlink.transport.TransportRouter

/** Input pipeline for one screen: gestures and on-screen buttons to mouse reports, S Pen to pen reports. */
class PadSession(context: Context, router: TransportRouter) {

    private val scheduler = MainThreadScheduler()
    private val haptics = Haptics(context)
    private val density = context.resources.displayMetrics.density

    /** Mouse state shared by gestures, buttons, the scroll strip, and pointer-mode pen. */
    val mouse = MouseController(density, scheduler, router::sendMouse, haptics::tick)

    private val gestures = GestureEngine(mouse, GestureConfig(slopPx = SLOP_DP * density))

    /** S Pen pipeline. */
    val pen = PenController(mouse, router::sendPen)

    /** Finger/stylus router with palm rejection. */
    val touch = TouchRouter(gestures, pen, scheduler)

    /** Applies settings and the wired host size to every stage. */
    fun apply(settings: Settings, hostSize: HostSize?) {
        mouse.applySettings(settings.mouse)
        gestures.config = gestures.config.copy(tapClicks = settings.mouse.touchClicks)
        pen.configure(settings.pen, hostSize)
        haptics.enabled = settings.mouse.haptics
    }

    /** Connects a touch surface to this session. */
    fun attach(view: TrackpadView) {
        view.input = StylusInput(view, touch)
        view.sizeListener = { w, h -> pen.setSurfaceSize(w, h) }
    }

    /** Lifts the pen and releases every held button (screen leaves the foreground). */
    fun release() {
        touch.reset()
        mouse.releaseAll()
    }

    private companion object {
        /** Touch slop in dp before a touch counts as motion. */
        const val SLOP_DP = 8f
    }
}
