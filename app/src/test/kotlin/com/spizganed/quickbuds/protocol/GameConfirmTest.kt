package com.spizganed.quickbuds.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The game-mode confirmation: a push by default, a status read for a model that never pushes. */
class GameConfirmTest {

    @Test fun aModelWithoutTheKeyWaitsForThePush() {
        assertEquals(FeatureSync.Confirm.PUSH, FeatureSyncConfig.gameConfirm(null))
        assertEquals(FeatureSync.Confirm.PUSH, FeatureSyncConfig.gameConfirm(JSONObject("""{"id":"X"}""")))
        assertEquals(FeatureSync.Confirm.PUSH, FeatureSyncConfig.gameConfirm(JSONObject("""{"gameConfirm":"push"}""")))
    }

    @Test fun aModelThatNeverPushesReadsTheState() {
        assertEquals(FeatureSync.Confirm.READ, FeatureSyncConfig.gameConfirm(JSONObject("""{"gameConfirm":"read"}""")))
    }
}
