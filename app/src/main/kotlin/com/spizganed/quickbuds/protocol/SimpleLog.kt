package com.spizganed.quickbuds.protocol

/**
 * Dev tools › Simple: a log line in plain words, or null when it is protocol plumbing (queries, acks,
 * handshake). Works on the lines [com.spizganed.quickbuds.bluetooth.BudsConnectionManager] logs, so a
 * new log line there shows here only once it gets a case below. Dev tools stay English-only.
 */
object SimpleLog {

    /** What the line is about; Dev tools gives each its own colour. */
    enum class Kind { SENT, RECEIVED, CONNECTION, PROBLEM }

    class Entry(val text: String, val kind: Kind)

    /** `0x0403` feature ids (PROTOCOL.md §9) by the name the app shows. */
    private val FEATURES = mapOf(
        0x04 to "Auto play/pause", 0x06 to "Low latency mode", 0x09 to "Vocal enhancement", 0x0B to "Hearing profile",
        0x0C to "Personalised noise cancelling", 0x11 to "Dual connection", 0x17 to "Power saving",
        0x18 to "High-quality audio", 0x1A to "Wind noise reduction", 0x1B to "3D audio", 0x1C to "Smart volume",
        0x1D to "Bass boost", 0x27 to "Game sound effects", 0x30 to "Adaptive volume", 0x31 to "Adaptive left / right ear",
        0x32 to "Conversation awareness", 0x35 to "Touch-and-hold volume", 0x37 to "Windows Swift Pair",
        0x38 to "Adaptive sound", 0x3A to "Pause when asleep", 0x3B to "Head gestures"
    )

    fun of(msg: String): Entry? {
        fun sent(t: String) = Entry(t, Kind.SENT)
        fun got(t: String) = Entry(t, Kind.RECEIVED)
        fun link(t: String) = Entry(t, Kind.CONNECTION)
        fun problem(t: String) = Entry(t, Kind.PROBLEM)
        val after = msg.substringAfter(": ", "")
        return when {
            msg.startsWith("TX[") -> sentLabel(msg.substring(3, msg.indexOf(']').coerceAtLeast(3)))?.let(::sent)
            msg.startsWith("RX") -> battery(msg)?.let(::got)
            msg.startsWith("Initiating RFCOMM connection to ") ->
                link("Connecting to " + msg.removePrefix("Initiating RFCOMM connection to ").removeSuffix("..."))
            msg.startsWith("Connected via") -> link("Connected")
            msg == "Disconnected" -> link("Disconnected")
            msg.startsWith("Connection lost") -> link("Connection lost")
            msg == "Case closed" -> link("Case closed, the buds disconnected")
            msg.startsWith("Reconnect after loss") -> Regex("(\\d+)/5").find(msg)?.let { link("Reconnecting, try ${it.groupValues[1]} of 5") }
                ?: problem("Stopped reconnecting after 5 tries")
            msg.startsWith("Auto-retry") -> link("Could not connect, trying again")
            msg.startsWith("Giving up") || msg.startsWith("All connection methods failed") -> problem("Could not connect to the buds")
            msg.startsWith("Bridge on") -> link("Bridge on")
            msg == "Bridge off" -> link("Bridge off")
            msg.startsWith("PRODUCT ID:") -> got("Buds: " + (Regex("model: ([^,]+)").find(msg)?.groupValues?.get(1) ?: "unknown model"))
            msg.startsWith("FIRMWARE:") -> got("Firmware version " + after.substringBefore(" "))
            msg.startsWith("WEAR ") -> got(wear(after))
            msg.startsWith("ANC QUERY:") || msg.startsWith("ANC EVT:") -> got("Noise control: " + msg.substringAfterLast("-> "))
            msg.startsWith("GAME EVT:") -> got("Low latency mode " + if (after == "ON") "on" else "off")
            msg.startsWith("FEATURES:") -> got(features(after))
            msg.startsWith("ALERT VOLUME:") -> got("Alert sound volume $after")
            msg.startsWith("TAP LEVEL:") -> got("Tap sensitivity " + after.substringBefore(" "))
            msg.startsWith("DEVICES:") -> got(devices(after))
            msg.startsWith("BTN EVT:") -> got(gesture(after))
            msg.startsWith("FIT:") -> got(fit(msg))
            msg.startsWith("KEYFN:") -> got("Gestures read")
            msg.startsWith("KEYFN DIFF:") && "NO CHANGE" !in msg -> got("Gestures differ from the first reading")
            msg.startsWith("EQ: current=") -> got("Equalizer read")
            msg.startsWith("CODEC:") -> got("Audio codec read")
            msg.startsWith("PERSONAL ANC result") -> got("Personalised noise cancelling test finished")
            msg.startsWith("GOLDEN STATUS") -> got("Hearing test update")
            msg.startsWith("ERROR ") -> problem("Error: " + msg.removePrefix("ERROR "))
            msg.startsWith("UNATTR") || msg.startsWith("DISCARDED") ->
                problem("The buds sent something QuickBuds does not understand (see Detailed)")
            "failed" in msg || "error" in msg || "refused" in msg || "nothing sent" in msg || "not sent" in msg ||
                "no active connection" in msg || "not connected" in msg -> problem("Problem: $msg")
            else -> null
        }
    }

    /** What a write was for, from its TX label; null for reads and protocol steps. */
    private fun sentLabel(label: String): String? {
        val number = Regex("-?\\d+").find(label)?.value
        return when {
            label.startsWith("ANC ") -> "Noise control set to " + label.removePrefix("ANC ")
            label == "GameMode" -> "Low latency mode switched"
            label.startsWith("Feature ") -> Regex("0x(\\w+) -> (\\w+)").find(label)?.let {
                (FEATURES[it.groupValues[1].toInt(16)] ?: "A setting") + if (it.groupValues[2] == "true") " turned on" else " turned off"
            }
            label.startsWith("Dual device") -> "Dual connection turned " + if (label.endsWith("true")) "on" else "off"
            label.startsWith("Connect ") -> "Asked the buds to connect a device"
            label.startsWith("Disconnect ") -> "Asked the buds to disconnect a device"
            label.startsWith("Preferred") -> "Preferred device changed"
            label.startsWith("EQ built-in") -> "Equalizer preset changed"
            label.startsWith("EQ custom") -> "Custom equalizer preset " + label.substringAfter(" '").removeSuffix("'") + " saved"
            label.startsWith("EQ create") -> "Custom equalizer preset " + label.substringAfter(" '").removeSuffix("'") + " created"
            label.startsWith("EQ delete") -> "Custom equalizer preset " + label.substringAfter(" '").removeSuffix("'") + " deleted"
            label.startsWith("BassWave level") -> "Bass boost level set to $number"
            label.startsWith("Find tone") -> if (label.endsWith("true")) "Ringing the earbuds" else "Stopped ringing the earbuds"
            label.startsWith("Fit test") -> if (label.endsWith("start")) "Fit test started" else "Fit test stopped"
            label.startsWith("Alert volume") -> "Alert sound volume set to $number"
            label.startsWith("Tap level") -> "Tap sensitivity set to $number"
            label.startsWith("Codec") -> "Audio codec changed, the buds restart"
            label.startsWith("Spatial type") -> "3D audio mode changed"
            label.startsWith("Game sound type") -> "Game sound mode changed"
            label.startsWith("Hold ANC modes") -> "Noise modes for the hold gesture changed"
            label == "set key function" || label.startsWith("Both-buds") -> "Gesture changed"
            label.startsWith("Personalized ANC") -> "Personalised noise cancelling test step"
            label == "manual status" -> "Asked the buds for their settings"
            else -> null
        }
    }

    /** Battery from a query reply (`0x8106`) or a push (`0x0204` subType 01). */
    private fun battery(msg: String): String? {
        val bytes = msg.substringAfter(": ", "").split(' ').mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        if (bytes.size < 10) return null
        val r = when (OpoProtocol.u16(bytes, 4)) {
            0x8106 -> BatteryParser.parse(bytes)
            OpoProtocol.CMD_ACTIVE_REPORT -> if (bytes[9].toInt() == BatteryParser.EVT_BATTERY) BatteryParser.parseActive(bytes) else null
            else -> null
        } ?: return null
        val parts = listOfNotNull(r.left?.let { "left" to it }, r.right?.let { "right" to it }, r.case?.let { "case" to it })
        if (parts.isEmpty()) return null
        return "Battery: " + parts.joinToString { (name, i) -> "$name ${i.level}%" + if (i.isCharging) " (charging)" else "" }
    }

    /** `L=EAR R=out case=CASE` (BudsConnectionManager.wearLabel). */
    private fun wear(s: String): String = s.split(' ').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val name = when (k) { "L" -> "Left earbud"; "R" -> "Right earbud"; else -> return@mapNotNull null }
        val state = when (v) { "EAR" -> "in ear"; "CASE" -> "in the case"; "out" -> "out of the ear"; else -> null }
        state?.let { "$name $it" }
    }.joinToString(", ").ifEmpty { "Wear state unknown" }

    /** `05=0 04=0 0B=1 …`: the switches that are on, by name. */
    private fun features(s: String): String {
        val on = s.split(' ').mapNotNull { p ->
            val (id, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            FEATURES[id.toIntOrNull(16)]?.takeIf { v == "1" }
        }
        return if (on.isEmpty()) "Settings read: none switched on" else "Settings on: " + on.joinToString()
    }

    /** `Name MAC on, Name MAC off`: the connected ones. */
    private fun devices(s: String): String {
        val on = s.split(", ").filter { it.endsWith(" on") }.map { it.removeSuffix(" on").substringBeforeLast(' ') }
        return if (on.isEmpty()) "No devices connected to the buds" else "Devices connected to the buds: " + on.joinToString()
    }

    /** `L btn=0x01 double tap ctx=…` (UserInteractionParser.describe). */
    private fun gesture(s: String): String {
        val side = when (s.substringBefore(' ')) { "L" -> "Left earbud"; "R" -> "Right earbud"; else -> "Earbud" }
        val action = listOf("single tap", "double tap", "triple tap", "long press", "slide up", "slide down").firstOrNull { it in s }
        return "$side: ${action ?: "gesture"}"
    }

    /** `FIT: left=1 right=6`: 1 good, 0 average, 6 poor (PROTOCOL.md §9). */
    private fun fit(s: String): String {
        fun word(v: String?) = when (v) { "1" -> "good fit"; "0" -> "average fit"; "6" -> "poor fit"; else -> "no result" }
        val l = Regex("left=(-?\\d+)").find(s)?.groupValues?.get(1)
        val r = Regex("right=(-?\\d+)").find(s)?.groupValues?.get(1)
        return "Fit test: left ${word(l)}, right ${word(r)}"
    }
}
