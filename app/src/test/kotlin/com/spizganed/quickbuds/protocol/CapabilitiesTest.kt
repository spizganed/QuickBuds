package com.spizganed.quickbuds.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The `0x8100` bit table matches `core/tests/bitmap_table.json`, which core checks too. */
class CapabilitiesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun onlyBit(bit: Int) = ByteArray(10).also { it[1 + bit / 8] = (1 shl (bit % 8)).toByte() }

    @Test fun everyBitMatchesCore() {
        val file = File("../core/tests/bitmap_table.json")
        assertTrue("missing contract: ${file.absolutePath}", file.isFile)
        val json = JSONObject(file.readText())
        fun hex(a: JSONArray) = (0 until a.length()).map { a.getString(it).removePrefix("0x").toInt(16) }
        val always = hex(json.getJSONArray("always")).toSet()
        val bits = json.getJSONArray("bits")
        assertEquals(always, Capabilities.parse(ByteArray(10)))
        for (bit in 0 until bits.length()) {
            assertEquals("bit $bit", always + hex(bits.getJSONArray(bit)), Capabilities.parse(onlyBit(bit)))
        }
        assertEquals("bits past the table are ignored", always, Capabilities.parse(onlyBit(bits.length())))
    }

    @Test fun lateBitsEnableTheirCommands() {
        assertTrue(0x0419 in Capabilities.parse(onlyBit(35))!!)
        assertEquals(setOf(0x0136, 0x0433), setOf(0x0136, 0x0433).filter { it in Capabilities.parse(onlyBit(64))!! }.toSet())
        assertTrue(listOf(0x0432, 0x0135, 0x0435).all { it in Capabilities.parse(onlyBit(67))!! })
        assertTrue(0x0117 in Capabilities.parse(onlyBit(68))!!)
        assertTrue(0x0419 !in Capabilities.parse(onlyBit(34))!!)
    }

    @Test fun productIdFoldsTheColourRanges() {
        for ((id, text) in listOf(0x100100 to "060414", 0x100102 to "060414", 0x100200 to "060814",
            0x108100 to "068414", 0x108202 to "068814", 0x065414 to "065414")) {
            val p = byteArrayOf(0, (id and 0xFF).toByte(), ((id shr 8) and 0xFF).toByte(), ((id shr 16) and 0xFF).toByte())
            assertEquals(text, Capabilities.productId(p))
        }
    }

    @Test fun buds4Capture() {
        val c = Capabilities.parse(bytes(0, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07))!!
        assertTrue(0x012F in c && 0x0427 in c && 0x0100 in c)
        assertTrue(0x0422 !in c)
    }

    @Test fun malformedIsNull() {
        assertNull(Capabilities.parse(bytes(1, 0xFF)))
        assertNull(Capabilities.parse(bytes(0)))
    }
}
