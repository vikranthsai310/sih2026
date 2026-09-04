package org.itantra.bench

import java.util.Locale

/**
 * `resource.csv` — sampled processor, memory, battery and thermal state. Task **W6.18**,
 * schema in `docs/EVALUATION.md` section 6.
 *
 * ## What this is really for
 *
 * The efficiency criterion needs idle and active figures, but the number that decides
 * whether the system works is neither: it is **what happens after half an hour**. An
 * entry-tier handset throttles after roughly ten minutes of continuous inference and its
 * real-time factor can double, so a cold measurement is not the measurement anyone in the
 * field will experience — requirement N9, risk T-03.
 *
 * That is why `thermal_status` is a column rather than a footnote, and why
 * [ResourceRun.Summary.throttlingBeganAtMillis] exists: the moment throttling starts is the single most
 * useful thing this file records, and finding it in week 8 would be finding it too late.
 */
class ResourceLogWriter(private val sink: Appendable) {
    private var wroteHeader = false
    private var rows = 0

    val rowCount: Int get() = rows

    fun write(sample: ResourceSample) {
        if (!wroteHeader) {
            sink.append(COLUMNS.joinToString(",")).append('\n')
            wroteHeader = true
        }
        val fields = sample.toFields()
        check(fields.size == COLUMNS.size) {
            "row has ${fields.size} fields, schema has ${COLUMNS.size}"
        }
        val offender = fields.indexOfFirst { f -> f.any { it == ',' || it == '\n' || it == '\r' } }
        check(offender < 0) {
            "field ${COLUMNS[offender]} contains a separator: '${fields[offender]}'"
        }
        sink.append(fields.joinToString(",")).append('\n')
        rows++
    }

    companion object {
        val COLUMNS =
            listOf(
                "timestamp",
                "cpu_pct",
                "rss_bytes",
                "battery_pct",
                "thermal_status",
                "state",
                "lang",
                "transport",
            )
    }
}

/**
 * One sample.
 *
 * @param thermalStatus the platform's `PowerManager` thermal status, 0–6
 * @param state the engine state at the moment of sampling, so a spike can be attributed
 */
data class ResourceSample(
    val timestampMillis: Long,
    val cpuPercent: Double,
    val rssBytes: Long,
    val batteryPercent: Int,
    val thermalStatus: Int,
    val state: String,
    val lang: String,
    val transport: String,
) {
    init {
        require(cpuPercent >= 0.0) { "processor use cannot be negative: $cpuPercent" }
        require(batteryPercent in 0..100) { "battery outside 0..100: $batteryPercent" }
        require(thermalStatus in 0..6) { "thermal status outside 0..6: $thermalStatus" }
    }

    /**
     * `THERMAL_STATUS_LIGHT` and above. At this point the platform has begun reducing
     * clocks and every timing figure taken from here on is a throttled one.
     */
    val isThrottling: Boolean get() = thermalStatus >= THROTTLING_THRESHOLD

    fun toFields(): List<String> =
        listOf(
            timestampMillis.toString(),
            String.format(Locale.ROOT, "%.1f", cpuPercent),
            rssBytes.toString(),
            batteryPercent.toString(),
            thermalStatus.toString(),
            state,
            lang,
            transport,
        )

    companion object {
        /** `PowerManager.THERMAL_STATUS_LIGHT`. */
        const val THROTTLING_THRESHOLD = 1
    }
}

/** Summarises a soak, the way the reporting rules need it stated. */
object ResourceRun {
    data class Summary(
        val samples: Int,
        val startedAtMillis: Long,
        val durationMillis: Long,
        val peakCpuPercent: Double,
        val peakRssBytes: Long,
        val batteryDrainPercent: Int,
        val throttlingBeganAtMillis: Long?,
        val maxThermalStatus: Int,
    ) {
        /** Whether the run was long enough to be reported as a soak at all. */
        val isSoak: Boolean get() = durationMillis >= SOAK_MILLIS

        /**
         * Minutes of work before the device began throttling, or null if it never did.
         * This is the figure worth quoting.
         */
        val minutesBeforeThrottling: Double?
            get() = throttlingBeganAtMillis?.let { (it - startedAtMillis) / 60_000.0 }
    }

    /** @return null for an empty run, rather than a summary of nothing. */
    fun summarise(samples: List<ResourceSample>): Summary? {
        if (samples.isEmpty()) return null
        val ordered = samples.sortedBy { it.timestampMillis }
        val first = ordered.first()
        val last = ordered.last()

        return Summary(
            samples = ordered.size,
            startedAtMillis = first.timestampMillis,
            durationMillis = last.timestampMillis - first.timestampMillis,
            peakCpuPercent = ordered.maxOf { it.cpuPercent },
            peakRssBytes = ordered.maxOf { it.rssBytes },
            // Battery only falls, so this is a drain rather than a difference that could
            // be negative — unless the handset was on charge, which invalidates the run.
            batteryDrainPercent = (first.batteryPercent - last.batteryPercent).coerceAtLeast(0),
            throttlingBeganAtMillis = ordered.firstOrNull { it.isThrottling }?.timestampMillis,
            maxThermalStatus = ordered.maxOf { it.thermalStatus },
        )
    }

    /**
     * A run taken with the handset on charge is not a valid endurance measurement, and
     * the battery rising is how you find out.
     */
    fun wasOnCharge(samples: List<ResourceSample>): Boolean {
        val ordered = samples.sortedBy { it.timestampMillis }
        return ordered.zipWithNext().any { (a, b) -> b.batteryPercent > a.batteryPercent }
    }

    /** `docs/TODO.md` W6.16: the first soak is thirty minutes. */
    const val SOAK_MILLIS = 30 * 60 * 1_000L
}
