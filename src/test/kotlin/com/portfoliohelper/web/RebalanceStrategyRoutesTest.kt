package com.portfoliohelper.web

import com.portfoliohelper.service.DerivedTargetScaleFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class RebalanceStrategyRoutesTest {
    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStepFunction() {
        val config = parseDerivedTargetScaleConfig(
            buildJsonObject {
                put("function", JsonPrimitive("HYSTERESIS_STEP"))
                put("referenceLower", JsonPrimitive(0.50))
                put("referenceUpper", JsonPrimitive(0.70))
                put("targetLower", JsonPrimitive(0.20))
                put("targetUpper", JsonPrimitive(1.00))
                put("stepBaseTarget", JsonPrimitive(0.95))
            }
        )

        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STEP, config.function)
        assertEquals(0.20, config.targetLower)
        assertEquals(0.95, config.stepBaseTarget)
    }

    @Test
    fun parseDerivedTargetScaleConfigKeepsHysteresisStairsFunction() {
        val config = parseDerivedTargetScaleConfig(
            buildJsonObject {
                put("function", JsonPrimitive("HYSTERESIS_STAIRS"))
                put("targetUpper", JsonPrimitive(1.00))
                put("stepBaseTarget", JsonPrimitive(0.95))
            }
        )

        assertEquals(DerivedTargetScaleFunction.HYSTERESIS_STAIRS, config.function)
        assertEquals(1.00, config.targetUpper)
        assertEquals(0.95, config.stepBaseTarget)
    }
}
