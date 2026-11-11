package com.spizganed.quickbuds.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The model data names the best high-quality codec, or none. */
class ModelCatalogCodecTest {
    @Test fun best() {
        assertEquals("LHDC 5.0", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["aac","lhdc5","ldac"]}""")))
        assertEquals("LHDC", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["sbc","lhdc","aac"]}""")))
        assertEquals("LDAC", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["sbc","ldac","aac"]}""")))
        assertEquals("aptX Adaptive", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["aptx","aptx-adaptive"]}""")))
        assertEquals("aptX HD", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["aptx-hd"]}""")))
        assertEquals("aptX", ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["aptx"]}""")))
        assertNull(ModelCatalog.bestHiResCodec(JSONObject("""{"id":"060414"}""")))
        assertNull(ModelCatalog.bestHiResCodec(JSONObject("""{"codecs":["aac","sbc"]}""")))
        assertNull(ModelCatalog.bestHiResCodec(null))
    }
}
