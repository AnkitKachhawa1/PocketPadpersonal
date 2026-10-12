package com.dathaze.pocketpad

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.util.Locale

/**
 * Debug recorder. While recording it notes every key and joystick-axis event Android
 * delivers (from any input device) and can turn them into one pasteable JSON report:
 * which physical presses produced which Android key code / raw scan code, which axes
 * moved, and what PocketPad currently does with each key.
 *
 * It only observes; it never changes how input is handled.
 */
class InputRecorder {

    var isRecording = false
        private set

    private class KeyRec(
        val keyCode: Int,
        val scanCode: Int,
        val device: String,
        val order: Int,
        val mapping: String
    ) {
        var downs = 0
        var ups = 0
    }

    private class AxisRec(val axis: Int, val device: String, val first: Float) {
        var min = first
        var max = first
        var negOrder = 0
        var posOrder = 0
    }

    private var startedAt = 0L
    private var order = 0
    private val keys = LinkedHashMap<String, KeyRec>()
    private val axes = LinkedHashMap<String, AxisRec>()
    private val devices = LinkedHashMap<Int, InputDevice>()
    private val log = ArrayList<String>()
    private var logDropped = 0

    fun start() {
        keys.clear(); axes.clear(); devices.clear(); log.clear()
        order = 0
        logDropped = 0
        startedAt = SystemClock.uptimeMillis()
        isRecording = true
    }

    /** Short text for the on-screen banner. */
    fun summary(): String {
        val moved = axes.values.count { it.max - it.min > 0.3f }
        return "keys ${keys.size} · axes $moved"
    }

    fun onKey(event: KeyEvent, mapping: String) {
        if (!isRecording || event.repeatCount > 0) return
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return
        val dev = InputDevice.getDevice(event.deviceId)
        if (dev != null) devices[dev.id] = dev
        val id = "${event.deviceId}/${event.keyCode}/${event.scanCode}"
        val rec = keys.getOrPut(id) {
            KeyRec(event.keyCode, event.scanCode, dev?.name ?: "device ${event.deviceId}", ++order, mapping)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) rec.downs++ else rec.ups++
        addLog("${if (down) "DOWN" else "UP  "} ${keyLabel(event.keyCode)} scan=${event.scanCode}")
    }

    fun onMotion(ev: MotionEvent) {
        if (!isRecording || ev.action != MotionEvent.ACTION_MOVE) return
        if (!ev.isFromSource(InputDevice.SOURCE_JOYSTICK)) return
        val dev = ev.device ?: return
        devices[dev.id] = dev
        for (range in dev.motionRanges) {
            val axis = range.axis
            val v = ev.getAxisValue(axis)
            val rec = axes.getOrPut("${dev.id}/$axis") { AxisRec(axis, dev.name, v) }
            if (v < rec.min) rec.min = v
            if (v > rec.max) rec.max = v
            if (rec.negOrder == 0 && v < -0.5f) {
                rec.negOrder = ++order
                addLog("AXIS ${axisLabel(axis)} -> ${fmt(v)} (negative side)")
            }
            if (rec.posOrder == 0 && v > 0.5f) {
                rec.posOrder = ++order
                addLog("AXIS ${axisLabel(axis)} -> ${fmt(v)} (positive side)")
            }
        }
    }

    /** Stop recording and return the report as JSON text. */
    fun stop(): String {
        isRecording = false
        return buildReport()
    }

    private fun addLog(text: String) {
        if (log.size >= MAX_LOG) {
            logDropped++
            return
        }
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000.0
        log.add(String.format(Locale.US, "%.2fs %s", t, text))
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun keyLabel(code: Int) =
        "${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")}($code)"

    private fun axisLabel(axis: Int) = MotionEvent.axisToString(axis).removePrefix("AXIS_")

    private fun hex(v: Int) = "0x" + Integer.toHexString(v)

    private fun buildReport(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("\"report\": \"PocketPad gamepad debug v1\",\n")
        sb.append("\"phone\": ${JSONObject.quote("${Build.MANUFACTURER} ${Build.MODEL}")},\n")
        sb.append("\"androidSdk\": ${Build.VERSION.SDK_INT},\n")
        sb.append("\"seconds\": ${(SystemClock.uptimeMillis() - startedAt) / 1000},\n")

        // Devices that produced events.
        sb.append("\"devices\": [\n")
        sb.append(devices.values.joinToString(",\n") { d ->
            val o = JSONObject()
            o.put("name", d.name)
            o.put("vendorId", hex(d.vendorId))
            o.put("productId", hex(d.productId))
            o.put("sources", hex(d.sources))
            o.put("axesSupported", org.json.JSONArray(d.motionRanges.map { r ->
                "${axisLabel(r.axis)}[${fmt(r.min)}..${fmt(r.max)}]"
            }))
            val have = d.hasKeys(*CHECK_KEYS)
            o.put("androidKeyLayoutHas", org.json.JSONArray(
                CHECK_KEYS.filterIndexed { i, _ -> have[i] }.map { keyLabel(it) }
            ))
            " " + o.toString()
        })
        sb.append("\n],\n")

        // Keys in the order they were first pressed.
        sb.append("\"keysInPressOrder\": [\n")
        sb.append(keys.values.joinToString(",\n") { k ->
            val o = JSONObject()
            o.put("n", k.order)
            o.put("androidKey", keyLabel(k.keyCode))
            o.put("rawScanCode", k.scanCode)
            o.put("downs", k.downs)
            o.put("ups", k.ups)
            o.put("pocketPadDoes", k.mapping)
            o.put("device", k.device)
            " " + o.toString()
        })
        sb.append("\n],\n")

        // Axes that actually moved, in the order they were first pushed.
        val moved = axes.values.filter { it.max - it.min > 0.3f }
            .sortedBy { minOf(it.negOrder.takeIf { n -> n > 0 } ?: Int.MAX_VALUE,
                it.posOrder.takeIf { n -> n > 0 } ?: Int.MAX_VALUE) }
        sb.append("\"axesThatMoved\": [\n")
        sb.append(moved.joinToString(",\n") { a ->
            val o = JSONObject()
            o.put("axis", axisLabel(a.axis))
            o.put("firstSeen", fmt(a.first))
            o.put("min", fmt(a.min))
            o.put("max", fmt(a.max))
            o.put("negativeAtStep", a.negOrder)
            o.put("positiveAtStep", a.posOrder)
            " " + o.toString()
        })
        sb.append("\n],\n")

        sb.append("\"log\": [\n")
        sb.append(log.joinToString(",\n") { " " + JSONObject.quote(it) })
        if (logDropped > 0) sb.append(",\n ${JSONObject.quote("... $logDropped more events not listed")}")
        sb.append("\n]\n}")
        return sb.toString()
    }

    private companion object {
        const val MAX_LOG = 300

        /** Key codes whose presence in the device's Android key layout is worth reporting. */
        val CHECK_KEYS = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
        ) + IntArray(16) { KeyEvent.KEYCODE_BUTTON_1 + it }
    }
}
