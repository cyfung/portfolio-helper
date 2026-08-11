package com.portfoliohelper.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class MonteCarloIndexedSimulationTest {
    @Test
    fun `selected blocks can wrap around the return pool`() {
        val paths = (0L until 100L).map { seed ->
            MonteCarloIndexedSimulation.assemblePath(
                rng = Random(seed),
                targetDays = 4,
                minChunkDays = 4,
                maxChunkDays = 4,
                poolSize = 5,
            ).returnIndexes.toList()
        }

        assertTrue(
            paths.any { path -> path.zipWithNext().any { (previous, next) -> previous == 3 && next == 0 } },
            "Expected a selected block to wrap from the final return index to index 0, got $paths",
        )
    }
}
