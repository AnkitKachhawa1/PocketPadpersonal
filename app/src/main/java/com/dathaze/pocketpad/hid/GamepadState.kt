package com.dathaze.pocketpad.hid

/**
 * Mutable gamepad state matching [HidConstants.REPORT_DESCRIPTOR].
 * Axes are 0..255 with 128 as center. Hat is 0..7 clockwise from up,
 * [HidConstants.HAT_NEUTRAL] when released.
 */
/** How the hat (d-pad) is presented to the host. */
enum class DpadMode {
    /** Hat only (original behaviour). */
    OFF,
    /** Hat is sent AND mirrored onto the left stick. */
    STICK_AND_HAT,
    /** Hat is mirrored onto the left stick and the hat itself is neutralised. */
    STICK_ONLY
}

class GamepadState {
    var buttons: Int = 0
    var hat: Int = HidConstants.HAT_NEUTRAL
    var lx: Int = 128
    var ly: Int = 128
    var rx: Int = 128
    var ry: Int = 128
    /** Analog triggers, 0 (released) .. 255 (fully pressed). */
    var lt: Int = 0
    var rt: Int = 0

    fun setButton(index: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or (1 shl index) else buttons and (1 shl index).inv()
    }

    fun isPressed(index: Int): Boolean = (buttons shr index) and 1 == 1

    fun reset() {
        buttons = 0
        hat = HidConstants.HAT_NEUTRAL
        lx = 128; ly = 128; rx = 128; ry = 128
        lt = 0; rt = 0
    }

    fun copyFrom(other: GamepadState) {
        buttons = other.buttons
        hat = other.hat
        lx = other.lx; ly = other.ly; rx = other.rx; ry = other.ry
        lt = other.lt; rt = other.rt
    }

    /** Serialize to the 9-byte HID input report payload. */
    fun toReport(): ByteArray = byteArrayOf(
        (buttons and 0xFF).toByte(),
        ((buttons shr 8) and 0xFF).toByte(),
        (hat and 0x0F).toByte(),
        (lx and 0xFF).toByte(),
        (ly and 0xFF).toByte(),
        (rx and 0xFF).toByte(),
        (ry and 0xFF).toByte(),
        (lt and 0xFF).toByte(),
        (rt and 0xFF).toByte()
    )

    companion object {
        /**
         * Merge touch input with an external (USB) controller into [out].
         * Buttons are OR-ed; for the hat and each stick, whichever source is
         * actually deflected wins (external wins ties so a plugged-in pad
         * always feels authoritative).
         */
        fun merge(touch: GamepadState, external: GamepadState, out: GamepadState) {
            out.buttons = touch.buttons or external.buttons
            out.hat = if (external.hat != HidConstants.HAT_NEUTRAL) external.hat else touch.hat
            out.lx = pickAxis(external.lx, touch.lx)
            out.ly = pickAxis(external.ly, touch.ly)
            out.rx = pickAxis(external.rx, touch.rx)
            out.ry = pickAxis(external.ry, touch.ry)
            // Whichever source is pushing the trigger harder wins.
            out.lt = maxOf(touch.lt, external.lt)
            out.rt = maxOf(touch.rt, external.rt)
        }

        // Diagonal d-pad stick values: 128 +/- 90 (~0.707 of full throw) keeps the
        // vector inside a circular gate. Set to 0 / 255 for full-corner output.
        private const val DIAG_LO = 38
        private const val DIAG_HI = 218

        /**
         * Copy [src] into [out], optionally mirroring the hat onto the LEFT stick.
         * [src] is never modified, so the real stick values are untouched. When
         * the hat is released, [out] simply equals [src] (physical stick or neutral).
         * Per axis, whichever of d-pad / physical stick is deflected further wins.
         * Axis convention matches the descriptor and the touch stick: Y 0 = up, 255 = down.
         */
        fun applyDpadMode(src: GamepadState, out: GamepadState, mode: DpadMode) {
            out.copyFrom(src)
            if (mode == DpadMode.OFF || src.hat !in 0..7) return
            val dx = when (src.hat) { 1, 2, 3 -> 1; 5, 6, 7 -> -1; else -> 0 }
            val dy = when (src.hat) { 7, 0, 1 -> -1; 3, 4, 5 -> 1; else -> 0 }
            val diag = dx != 0 && dy != 0
            val lo = if (diag) DIAG_LO else 0
            val hi = if (diag) DIAG_HI else 255
            if (dx != 0) out.lx = pickAxis(if (dx < 0) lo else hi, src.lx)
            if (dy != 0) out.ly = pickAxis(if (dy < 0) lo else hi, src.ly)
            if (mode == DpadMode.STICK_ONLY) out.hat = HidConstants.HAT_NEUTRAL
        }

        private fun pickAxis(external: Int, touch: Int): Int {
            val extDelta = kotlin.math.abs(external - 128)
            val touchDelta = kotlin.math.abs(touch - 128)
            return if (extDelta >= touchDelta) external else touch
        }
    }
}
