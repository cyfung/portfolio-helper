package com.portfoliohelper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlexibleWeightsTest {
    @Test
    fun `valid mapping JSON enables flexible rebalancing while invalid JSON does not`() {
        assertEquals(
            listOf(FlexibleWeightMapping("UPRO", "3 VOO")),
            parseFlexibleWeightMappings("""[{"left":" UPRO ","right":"3 VOO"}]"""),
        )
        assertTrue(hasFlexibleWeightMappings("""[{"left":"UPRO","right":"3 VOO"}]"""))
        assertFalse(hasFlexibleWeightMappings("""[{"left":"UPRO","right":""}]"""))
        assertFalse(hasFlexibleWeightMappings("not json"))
        assertFalse(hasFlexibleWeightMappings("""[{"left":"0 A","right":"B"}]"""))
        assertEquals(
            listOf(FlexibleWeightMapping("A", "B")),
            parseFlexibleWeightMappings("""[null,{"left":"A","right":"B"},{"left":"C"}]"""),
        )
    }

    @Test
    fun `one to one mapping offsets target deviations and rebalancing amounts`() {
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("A", currentWeightPct = 60.0, targetWeight = 50.0, rebalDollars = -100.0),
                FlexibleStockInput("B", currentWeightPct = 40.0, targetWeight = 50.0, rebalDollars = 100.0),
            ),
            mappings = listOf(FlexibleWeightMapping("A", "B")),
        )

        assertEquals(60.0, result.targetWeight.getValue("A"), 1e-9)
        assertEquals(40.0, result.targetWeight.getValue("B"), 1e-9)
        assertEquals(0.0, result.rebalDollars.getValue("A"), 1e-9)
        assertEquals(0.0, result.rebalDollars.getValue("B"), 1e-9)
    }

    @Test
    fun `weighted mapping applies expression units like desktop`() {
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("A", currentWeightPct = 60.0, targetWeight = 40.0, rebalDollars = -20.0),
                FlexibleStockInput("B", currentWeightPct = 40.0, targetWeight = 60.0, rebalDollars = 10.0),
            ),
            mappings = listOf(FlexibleWeightMapping("2 A", "B")),
        )

        assertEquals(60.0, result.targetWeight.getValue("A"), 1e-9)
        assertEquals(50.0, result.targetWeight.getValue("B"), 1e-9)
        assertEquals(0.0, result.rebalDollars.getValue("A"), 1e-9)
        assertEquals(0.0, result.rebalDollars.getValue("B"), 1e-9)
    }

    @Test
    fun `composite and duplicate legs use normalized expression units`() {
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("A", 60.0, 50.0, -10.0),
                FlexibleStockInput("B", 30.0, 20.0, -10.0),
                FlexibleStockInput("C", 10.0, 30.0, 20.0),
            ),
            mappings = listOf(FlexibleWeightMapping("A A B", "C")),
        )

        assertEquals(60.0, result.targetWeight.getValue("A"), 1e-9)
        assertEquals(25.0, result.targetWeight.getValue("B"), 1e-9)
        assertEquals(25.0, result.targetWeight.getValue("C"), 1e-9)
        assertEquals(0.0, result.rebalDollars.getValue("A"), 1e-9)
        assertEquals(-5.0, result.rebalDollars.getValue("B"), 1e-9)
        assertEquals(15.0, result.rebalDollars.getValue("C"), 1e-9)
    }

    @Test
    fun `bidirectional transitive mappings can settle all three instruments`() {
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("A", 60.0, 30.0, -30.0),
                FlexibleStockInput("B", 20.0, 30.0, 10.0),
                FlexibleStockInput("C", 20.0, 40.0, 20.0),
            ),
            mappings = listOf(
                FlexibleWeightMapping("A", "B"),
                FlexibleWeightMapping("B", "C"),
                FlexibleWeightMapping("C", "A"),
            ),
        )

        assertEquals(mapOf("A" to 60.0, "B" to 20.0, "C" to 20.0), result.targetWeight)
        assertEquals(mapOf("A" to 0.0, "B" to 0.0, "C" to 0.0), result.rebalDollars)
    }

    @Test
    fun `missing zero and sub epsilon values do not manufacture shifts`() {
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("A", 50.0000000001, 50.0, 0.0000000001),
                FlexibleStockInput("B", 50.0, 50.0, 0.0),
            ),
            mappings = listOf(
                FlexibleWeightMapping("A", "MISSING"),
                FlexibleWeightMapping("A", "B"),
            ),
        )

        assertEquals(50.0, result.targetWeight.getValue("A"), 1e-9)
        assertEquals(0.0000000001, result.rebalDollars.getValue("A"), 1e-12)
        assertFalse("MISSING" in result.targetWeight)
    }

    @Test
    fun `expression expansion limit terminates a long mapping chain`() {
        val mappings = (0..110).map { index ->
            FlexibleWeightMapping("S$index", "S${index + 1}")
        }
        val result = computeFlexibleStockDisplay(
            stocks = listOf(
                FlexibleStockInput("S0", 60.0, 50.0, -10.0),
                FlexibleStockInput("S111", 40.0, 50.0, 10.0),
            ),
            mappings = mappings,
        )

        assertTrue(result.targetWeight.values.all(Double::isFinite))
        assertTrue(result.rebalDollars.values.all(Double::isFinite))
    }
}
