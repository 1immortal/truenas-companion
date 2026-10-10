package app.truenascompanion.data.runway

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.truenascompanion.data.model.Pool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * 1.10.0 storage runway. TrueNAS 25.10 keeps no pool-usage history (its netdata graphs cover CPU, memory, disks,
 * network, ARC and UPS, but not pool capacity), so the phone takes one sample per pool per day and keeps two years.
 */
@Serializable
data class UsageSample(val day: Long, val used: Long, val size: Long)

/** Per pool name, oldest first. */
@Serializable
data class RunwaySamples(val pools: Map<String, List<UsageSample>> = emptyMap()) {
    val lastDay: Long? get() = pools.values.mapNotNull { it.lastOrNull()?.day }.maxOrNull()

    /** Adds today's sample (replacing an earlier one from the same day) and drops what's older than [KEEP_DAYS]. */
    fun record(pools: List<Pool>, today: Long): RunwaySamples {
        val next = this.pools.toMutableMap()
        pools.forEach { p ->
            val size = p.size ?: return@forEach
            val used = p.allocated ?: return@forEach
            if (size <= 0) return@forEach
            val list = next[p.name].orEmpty().filter { it.day != today && it.day > today - KEEP_DAYS }
            next[p.name] = list + UsageSample(today, used, size)
        }
        // Pools that disappeared are kept until their samples age out (an exported pool may come back).
        return RunwaySamples(next.mapValues { (_, l) -> l.filter { it.day > today - KEEP_DAYS } }.filterValues { it.isNotEmpty() })
    }

    companion object { const val KEEP_DAYS = 730L }
}

sealed interface RunwayForecast {
    /** Fewer than [Runway.MIN_DAYS] days of samples. */
    data class Collecting(val days: Int) : RunwayForecast
    /** Usage isn't growing (flat or shrinking). */
    data object NotGrowing : RunwayForecast
    /** [daysLeft] until the pool is full at the current growth ([bytesPerDay]). */
    data class Full(val daysLeft: Long, val bytesPerDay: Double) : RunwayForecast
    /** Already full (or past it). */
    data object AlreadyFull : RunwayForecast
}

object Runway {
    const val MIN_DAYS = 7
    /** The trend uses the most recent half year, so an old migration doesn't dominate. */
    const val TREND_DAYS = 180L

    fun today(zone: ZoneId = ZoneId.systemDefault()): Long = LocalDate.now(zone).toEpochDay()

    /** Robust (Theil–Sen) trend of used bytes per day over the recent samples. */
    fun forecast(samples: List<UsageSample>): RunwayForecast {
        if (samples.isEmpty()) return RunwayForecast.Collecting(0)
        val last = samples.last()
        val recent = samples.filter { it.day > last.day - TREND_DAYS }.takeLast(120)
        val days = recent.map { it.day }.distinct().size
        if (days < MIN_DAYS || recent.last().day - recent.first().day < MIN_DAYS - 1) return RunwayForecast.Collecting(days)
        val slopes = ArrayList<Double>(recent.size * recent.size / 2)
        for (i in recent.indices) for (j in i + 1 until recent.size) {
            val dx = (recent[j].day - recent[i].day).toDouble()
            if (dx > 0) slopes += (recent[j].used - recent[i].used) / dx
        }
        if (slopes.isEmpty()) return RunwayForecast.Collecting(days)
        val slope = median(slopes)
        val intercept = median(recent.map { it.used - slope * it.day })
        val nowUsed = intercept + slope * last.day
        if (nowUsed >= last.size || last.used >= last.size) return RunwayForecast.AlreadyFull
        // Less than ~1 MiB a day, or shrinking: not meaningfully growing.
        if (slope < 1_048_576.0) return RunwayForecast.NotGrowing
        val left = ((last.size - nowUsed) / slope).roundToLong().coerceAtLeast(0)
        return RunwayForecast.Full(left, slope)
    }

    fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** "About 7 months until full" (honest about uncertainty: rounded, and capped at 10 years). */
    fun text(f: RunwayForecast): String = when (f) {
        is RunwayForecast.Collecting -> "Collecting data: ${f.days} of $MIN_DAYS days so far"
        RunwayForecast.NotGrowing -> "Not filling up right now"
        RunwayForecast.AlreadyFull -> "Full"
        is RunwayForecast.Full -> when {
            f.daysLeft < 1 -> "Full within a day at this rate"
            f.daysLeft < 45 -> "About ${f.daysLeft} day${if (f.daysLeft == 1L) "" else "s"} until full"
            f.daysLeft < 730 -> "About ${(f.daysLeft / 30.4).roundToLong()} months until full"
            f.daysLeft < 3650 -> "About ${(f.daysLeft / 365.0).roundToLong()} years until full"
            else -> "More than 10 years until full"
        }
    }
}

private val Context.runwayStore: DataStore<Preferences> by preferencesDataStore(name = "runway_samples")

/** Samples live in their own small DataStore file, so the main settings file stays small. */
class RunwayStore(context: Context) {
    private val store = context.applicationContext.runwayStore
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(serverId: String) = stringPreferencesKey("samples_$serverId")

    private fun decode(p: Preferences, serverId: String): RunwaySamples =
        p[key(serverId)]?.let { runCatching { json.decodeFromString<RunwaySamples>(it) }.getOrNull() } ?: RunwaySamples()

    fun samples(serverId: String): Flow<RunwaySamples> = store.data.map { decode(it, serverId) }.distinctUntilChanged()

    suspend fun lastDay(serverId: String): Long? = decode(store.data.first(), serverId).lastDay

    /** At most one sample per pool per day; a later one the same day replaces it. Returns false when nothing changed. */
    suspend fun record(serverId: String, pools: List<Pool>, today: Long = Runway.today()): Boolean {
        if (pools.isEmpty()) return false
        var changed = false
        store.edit { p ->
            val cur = decode(p, serverId)
            val next = cur.record(pools, today)
            if (next != cur) { p[key(serverId)] = json.encodeToString(RunwaySamples.serializer(), next); changed = true }
        }
        return changed
    }

    suspend fun delete(serverId: String) { store.edit { it.remove(key(serverId)) } }
}
