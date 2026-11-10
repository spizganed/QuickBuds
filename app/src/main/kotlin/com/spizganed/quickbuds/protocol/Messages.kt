package com.spizganed.quickbuds.protocol

/**
 * Typed messages of docs/PROTOCOL.md: the value types, the command bitmap and the message
 * catalogue. The decoder is in Decode.kt. The Rust port is `core/src/msg.rs`.
 */

/** A MAC in written order. */
typealias Mac = List<Int>

/** One battery cell: part 1 left, 2 right, 3 case. */
data class Cell(val part: Int, val level: Int, val charging: Boolean)

sealed class Anc {
    /** The current mode as a bitmask (the report table, not the SET table). */
    data class Mode(val mask: Int) : Anc()
    /** The modes the hold cycles: [kind] 1 shared, 3 left, 4 right; [mask] in SET bits. */
    data class HoldCycle(val kind: Int, val mask: Int) : Anc()
    /** The level Smart chose, in SET bits. */
    data class Smart(val mask: Int) : Anc()
    /** `01 02 <level>`: a level report, never seen on our buds. */
    data class Level(val level: Int) : Anc()
}

/** One gesture binding: [dev] 1 left, 2 right, 4 both. */
data class Binding(val dev: Int, val button: Int, val act: Int, val func: Int)

/** One custom equaliser preset. [range] is echoed back on a save: -6 to 6 is `FA 06`. [bands] are (Hz, dB). */
data class EqPreset(val id: Int, val name: String, val selected: Boolean, val range: Pair<Int, Int>, val bands: List<Pair<Int, Int>>)

data class Device(val mac: Mac, val name: String, val connected: Boolean, val flags: Int) {
    val thisPhone get() = flags and 1 != 0
}

data class Linked(val mac: Mac, val times: Int, val name: String)

sealed class Public {
    data class AlertVolume(val level: Int) : Public()
    /** [auto]: the buds pick the device. [mac]: the chosen handheld. */
    data class Handheld(val auto: Boolean, val mac: Mac?) : Public()
    data class TapLevel(val level: Int) : Public()
    data class Other(val flags: Int) : Public()
}

/** Push `0D`. Each field is present when its flag is set and the bytes are there. */
data class Triangle(
    val capability: Int? = null, val version: Int? = null, val wear: Int? = null,
    /** Bit 0 phone has no active command, 1 connected by the related device, 2 HFP busy, 3 A2DP busy, 4 other device's auto switch closed. */
    val status: Int? = null, val relatedName: String? = null,
)

/** Both ears' biquad filters of a Golden Sound result: sample rate, left, right. */
data class Curves(val rate: Int, val left: List<Float>, val right: List<Float>)

/** A request from the buds, which they expect to be answered (PROTOCOL.md section 11). */
sealed class Request {
    /** `0x0500`: the phone's clock. */
    object Time : Request()
    /** `0x050C`: is the foreground app on a list the buds hold. */
    object AppList : Request()
    /** `0x051C`: the app's capabilities. */
    object Capabilities : Request()
    /** `0x051D`: the phone's spatial mode. */
    object SpatialMode : Request()
    /** `0x0501` and `0x0502`: camera state and shutter in HeyMelody; realme reads `0x0502 05`/`06` as find phone. Not answered. */
    data class Camera(val cmd: Int) : Request()
    /** A command outside the known groups. */
    data class Unknown(val cmd: Int) : Request()
}

/** The commands the buds accept. Before the reply, every command is allowed. */
class Bitmap private constructor(private val cmds: Set<Int>?) {
    fun allows(cmd: Int) = cmds?.contains(cmd) ?: true
    val known get() = cmds != null

    companion object {
        val UNKNOWN = Bitmap(null)

        /** Commands behind each bit of the `0x8100` reply (PROTOCOL.md section 5). */
        private val BITS: List<List<Int>> = listOf(
            listOf(0x0105), listOf(0x0106), listOf(0x0107), listOf(0x0108, 0x0401, 0x0416), listOf(0x0109), listOf(0x0400),
            listOf(0x0402), listOf(0x0403), listOf(0x010C, 0x0404), listOf(0x0405), listOf(0x0406, 0x010F), listOf(0x0407),
            listOf(), listOf(0x0408), listOf(0x0409), listOf(), listOf(), listOf(0x0114), listOf(),
            listOf(0x040E, 0x040D, 0x0115, 0x0116), listOf(0x040F), listOf(0x0410, 0x0119), listOf(0x0205), listOf(0x0F00),
            listOf(), listOf(0x0118, 0x0411), listOf(0x011A, 0x0412), listOf(0x011C, 0x0413), listOf(), listOf(0x0112, 0x040B),
            listOf(0x011E, 0x011F, 0x0415), listOf(0x040D), listOf(), listOf(0x0121, 0x0417), listOf(0x0122, 0x0418),
            listOf(0x0419), listOf(0x011D, 0x0414), listOf(0x0123, 0x041A), listOf(0x0124, 0x041B),
            listOf(0x0125, 0x041C, 0x0127, 0x041D, 0x041F), listOf(0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129),
            listOf(0xEF01), listOf(0xEF02), listOf(0xEF03, 0x041E), listOf(0x0420), listOf(0x001C), listOf(),
            listOf(0x0422, 0x012A), listOf(0xEF04), listOf(0x0423, 0x012B), listOf(), listOf(0x0424), listOf(0xEF06),
            listOf(), listOf(0x012C), listOf(0x0425, 0x012E, 0x0426), listOf(0x012F), listOf(0x0427, 0x0130), listOf(0x0131, 0x0428),
            listOf(0x0429, 0x0132), listOf(0x0014), listOf(0x042D, 0x0133), listOf(0x042E), listOf(0xEF07),
            listOf(0xEF08, 0x0136, 0x0433), listOf(0xEF09), listOf(0x0431, 0x0134), listOf(0x0432, 0x0135, 0x0435), listOf(0x0117),
        )
        private val ALWAYS = listOf(0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04)

        /** `00` then bytes, least significant bit first. Bits past the table are ignored. */
        fun parse(p: ByteArray): Bitmap? {
            if (p.size < 2 || p[0].toInt() != 0) return null
            val out = HashSet(ALWAYS)
            BITS.forEachIndexed { bit, cmds ->
                if (1 + bit / 8 < p.size && (p[1 + bit / 8].toInt() shr (bit % 8)) and 1 == 1) out += cmds
            }
            return Bitmap(out)
        }
    }
}

/** A decoded message from the buds. */
sealed class Msg {
    // Replies (PROTOCOL.md section 3)
    data class BitmapReply(val bitmap: Bitmap) : Msg()
    /** 24-bit product id, as the buds send it. */
    data class ProductId(val id: Int) : Msg()
    /** (device, version type, version); device 1 left, 2 right, 3 case. */
    data class Firmware(val parts: List<Triple<Int, Int, String>>) : Msg()
    data class Offered(val codes: List<Int>) : Msg()
    /** The `0x8205` reply. */
    object Registered : Msg() { override fun toString() = "Registered" }
    /** A write reply (`0x84xx`): the command written, the status (0 accepted) and the bytes after it. */
    data class Ack(val cmd: Int, val status: Int, val data: List<Int>) : Msg()
    data class Gestures(
        val table: List<Binding>,
        /** The status byte: 0 in a normal reply, -1 when the payload is too short. */
        val status: Int = 0,
        /** Bytes left after the whole entries; the parser keeps them. */
        val trailing: List<Int> = emptyList(),
    ) : Msg()
    /** `0x810D`: (feature id, value). */
    data class Features(val list: List<Pair<Int, Int>>) : Msg()
    data class EqCurrent(val id: Int) : Msg()
    data class EqCustom(val list: List<EqPreset>) : Msg()
    data class BassLevel(val level: Int) : Msg()
    /** `0x812C`: (band 1-3, level) pairs, realme dynamic sound. */
    data class BassBands(val list: List<Pair<Int, Int>>) : Msg()
    data class AlertVolume(val level: Int) : Msg()
    /** [default] is absent in a push. */
    data class TapLevel(val level: Int, val default: Int?) : Msg()
    /** `0x812A` and push `0x0510`: 0 off, 1 fixed, 2 head tracking. */
    data class Spatial(val type: Int) : Msg()
    data class Codec(val codec: Int) : Msg()
    /** Bit k of the mask is codec k + 1. */
    data class CodecMask(val mask: Int) : Msg()
    data class GameSound(val selected: Int, val offered: List<Int>) : Msg()
    data class PersonalAncStored(val stored: Boolean) : Msg()
    /** null: the buds choose. */
    data class Preferred(val mac: Mac?) : Msg()
    /** `0x8115`: the Golden Sound record on the buds. [values]: left ears 1-6, then right ears 1-6. */
    data class GoldenRecord(val id: Int, val name: String, val values: List<Int>) : Msg()
    /** `0x811E`: its ear-scan data. */
    data class GoldenScan(val id: Int, val data: List<Int>) : Msg()
    /** `0x8116`: the hearing filters of a result and its enhance type. */
    data class GoldenFilter(val id: Int, val enhance: Int, val curves: Curves?) : Msg()
    /** `0x811F`: the ear-scan filters of a record. */
    data class GoldenScanFilter(val id: Int, val curves: Curves) : Msg()
    data class Ask(val request: Request) : Msg()

    // Pushes (PROTOCOL.md section 10); the same facts also arrive as replies to a query
    data class Battery(val cells: List<Cell>) : Msg()
    data class Wear(val parts: List<Pair<Int, Int>>) : Msg()
    data class AncMsg(val anc: Anc) : Msg()
    data class FitTest(val left: Int?, val right: Int?) : Msg()
    /** Both candidates; [state] picks one by product id. */
    data class GameMode(val at1: Int?, val at3: Int?) : Msg() {
        /** The state for [productId]: [at3] for the four models, [at1] otherwise. null: no state byte. */
        fun state(productId: String?): Boolean? = GameModeParser.state(at1, at3, productId)
    }
    /** `0x0503`: feature id -> value; bytes after the last pair are ignored. */
    data class FeaturePush(val values: Map<Int, Int>) : Msg()
    data class Devices(val list: List<Device>) : Msg()
    data class Hearing(val kind: Int, val status: Int) : Msg()
    data class ZenState(val state: Int) : Msg()
    data class PersonalAnc(val result: Int) : Msg()
    data class TriangleMsg(val info: Triangle) : Msg()
    data class EarScan(val id: Int, val data: List<Int>) : Msg()
    data class PublicMsg(val event: Public) : Msg()
    data class Gesture(val side: Int, val button: Int, val act: Int, val func: Int, val context: Int, val options: List<Int>) : Msg()
    data class LinkedMsg(val list: List<Linked>) : Msg()
    data class Diagnostic(val json: String) : Msg()
    data class HeadGesture(val type: Int) : Msg()
    data class HeadTry(val result: Int) : Msg()
    /** [kind]: 0, 1 or 2 from flag bits 0-2; [flag]: bit 7. */
    data class VoiceAssist(val kind: Int?, val flag: Boolean) : Msg()
    data class LinkSwitch(val state: Int, val owner: Int, val reset: Int) : Msg()
    /** A push the spec names but no feature uses (10, 11, F3, F9, ...). */
    data class Unused(val code: Int, val data: List<Int>) : Msg()
}
