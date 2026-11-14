// PenController.kt: turns stylus samples into absolute PenReports (tablet mode) or mouse motion (pointer mode).
package dev.padlink.input

import dev.padlink.settings.PenAspect
import dev.padlink.settings.PenMode
import dev.padlink.settings.PenSettings
import dev.padlink.settings.SideButtonAction
import dev.padlink.transport.HostSize

/** One stylus sample in view pixels with raw Android axes. */
data class StylusSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltRad: Float = 0f,
    val orientationRad: Float = 0f,
    val touching: Boolean,
    val sideButton: Boolean = false,
    val eraserTool: Boolean = false,
)

/** Converts [StylusSample]s according to [PenSettings]. Must be used from a single thread. */
class PenController(
    private val mouse: MouseController,
    private val output: (PenReport) -> Unit,
) {
    private var settings = PenSettings()
    private var hostSize: HostSize? = null
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var inRange = false
    private var lastX = 0f
    private var lastY = 0f
    private var pointerButtons = 0

    /** Active tablet rectangle in view pixels (for the on-screen letterbox). */
    var area: TabletArea = TabletArea(0f, 0f, 0f, 0f)
        private set

    /** Applies pen settings and the wired host size; lifts the pen first when the mode changes. */
    fun configure(settings: PenSettings, hostSize: HostSize?) {
        if (settings.mode != this.settings.mode) leave()
        this.settings = settings
        this.hostSize = hostSize
        recomputeArea()
    }

    /** Updates the pen surface size in pixels. */
    fun setSurfaceSize(width: Int, height: Int) {
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        recomputeArea()
    }

    /** Target aspect ratio (width / height), or null to stretch over the whole surface. */
    fun targetAspect(): Float? = aspectFor(settings.aspect, hostSize)

    /** Processes one hover or contact sample. */
    fun sample(s: StylusSample) {
        when (settings.mode) {
            PenMode.TABLET -> tablet(s)
            PenMode.POINTER -> pointer(s)
        }
        inRange = true
    }

    /** The pen left proximity: lift it out of range (tablet) or release buttons (pointer). */
    fun leave() {
        if (!inRange) return
        inRange = false
        when (settings.mode) {
            PenMode.TABLET -> output(PenReport.OUT_OF_RANGE)
            PenMode.POINTER -> setPointerButtons(0)
        }
    }

    /** Emits an absolute report with flags derived from the tool, tip, and side button. */
    private fun tablet(s: StylusSample) {
        val (x, y) = TabletMapping.map(s.x, s.y, area)
        val erasing = s.eraserTool || (s.sideButton && settings.sideButton == SideButtonAction.ERASER)
        var flags = PenReport.IN_RANGE
        if (s.touching) flags = flags or PenReport.TIP
        if (erasing) flags = flags or PenReport.ERASER or PenReport.INVERT
        if (s.sideButton && settings.sideButton == SideButtonAction.BARREL && !s.eraserTool) {
            flags = flags or PenReport.BARREL
        }
        val pressure = if (s.touching) TabletMapping.pressure(s.pressure, settings.pressure.gamma) else 0
        val (tiltX, tiltY) = TabletMapping.tilt(s.tiltRad, s.orientationRad)
        output(PenReport(flags, x, y, pressure, tiltX, tiltY))
    }

    /** Moves the cursor relatively; the tip holds left, the side button holds right. */
    private fun pointer(s: StylusSample) {
        if (inRange) mouse.moveLinear(s.x - lastX, s.y - lastY)
        lastX = s.x
        lastY = s.y
        var wanted = 0
        if (s.touching) wanted = if (s.sideButton) MouseReport.RIGHT else MouseReport.LEFT
        setPointerButtons(wanted)
    }

    /** Presses and releases mouse buttons so exactly [mask] is held by the pen. */
    private fun setPointerButtons(mask: Int) {
        for (bit in intArrayOf(MouseReport.LEFT, MouseReport.RIGHT)) {
            val want = mask and bit != 0
            val have = pointerButtons and bit != 0
            if (want && !have) mouse.press(bit)
            if (!want && have) mouse.release(bit)
        }
        pointerButtons = mask
    }

    /** Recomputes [area] from the surface size, aspect, and area setting. */
    private fun recomputeArea() {
        area = TabletMapping.area(viewWidth, viewHeight, targetAspect(), settings.areaPercent)
    }

    companion object {
        /** Aspect used when `auto` has no host size (Bluetooth hosts do not report one). */
        const val DEFAULT_ASPECT = 16f / 9f

        /** Resolves the aspect setting: `auto` uses [hostSize] when known, `stretch` returns null. */
        fun aspectFor(aspect: PenAspect, hostSize: HostSize?): Float? = when (aspect) {
            PenAspect.STRETCH -> null
            PenAspect.AUTO -> hostSize?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height } ?: DEFAULT_ASPECT
            else -> aspect.ratio
        }
    }
}
