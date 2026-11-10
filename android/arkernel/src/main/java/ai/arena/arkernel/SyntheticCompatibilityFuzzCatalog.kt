package ai.arena.arkernel

/**
 * Bounded, seed-based scenarios for broader deterministic fault fuzzing.
 *
 * This catalog extends the existing healthy/single/pairwise catalogs with
 * reproducible pseudo-random fault subsets and sample budgets. It remains a
 * scalar replay source, not Camera2/IMU emulation, framework injection or
 * physical-device evidence.
 */
object SyntheticCompatibilityFuzzCatalog {
    private const val MULTIPLIER = 6364136223846793005L
    private const val INCREMENT = 1442695040888963407L

    fun seeded(
        seed: Long,
        count: Int = 64,
    ): List<SyntheticCompatibilityScenario> {
        require(count in 1..256) { "Synthetic fuzz scenario count is invalid" }
        var state = seed
        fun next(): Long {
            state = state * MULTIPLIER + INCREMENT
            return state
        }
        fun bounded(bound: Int): Int = ((next() ushr 1) % bound.toLong()).toInt()
        return List(count) { index ->
            val faultPool = SyntheticCompatibilityFault.entries.toMutableList()
            val faultCount = bounded(5)
            val faults = HashSet<SyntheticCompatibilityFault>(faultCount)
            repeat(faultCount) {
                faults.add(faultPool.removeAt(bounded(faultPool.size)))
            }
            SyntheticCompatibilityScenario(
                scenarioId = "fuzz-$seed-$index",
                faults = faults,
                // Keep injected frame gaps shorter than the fixed disjoint-window
                // offset so a disjoint-window fault cannot accidentally overlap.
                cameraSamples = 20 + bounded(65),
                imuSamplesPerSensor = 50 + bounded(300),
            )
        }
    }
}
