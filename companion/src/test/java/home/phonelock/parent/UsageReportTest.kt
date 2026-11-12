package home.phonelock.parent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** The report's arithmetic: which days a period covers, totals, and which parts the chart shows. */
class UsageReportTest {
    private val days = mapOf(
        "2026-11-09" to mapOf("yt" to 30, "game1" to 20, "game2" to 10),
        "2026-11-10" to mapOf("yt" to 45, "game1" to 5),
        "2026-11-30" to mapOf("yt" to 60),
    )

    @Test
    fun monthCoversEveryDay() {
        val d = periodDays(Period.MONTH, LocalDate.parse("2026-11-11"))
        assertEquals(30, d.size)
        assertEquals(LocalDate.parse("2026-11-01"), d.first())
        assertEquals(LocalDate.parse("2026-11-30"), d.last())
    }

    @Test
    fun weekHasSevenConsecutiveDaysAroundTheAnchor() {
        val anchor = LocalDate.parse("2026-11-11")
        val d = periodDays(Period.WEEK, anchor)
        assertEquals(7, d.size)
        assert(anchor in d)
        (1 until 7).forEach { assertEquals(d[it - 1].plusDays(1), d[it]) }
    }

    @Test
    fun totalsAddUpOverTheChosenDaysOnly() {
        val t = totalsOver(days, listOf(LocalDate.parse("2026-11-09"), LocalDate.parse("2026-11-10")))
        assertEquals(mapOf("yt" to 75, "game1" to 25, "game2" to 10), t)
    }

    @Test
    fun mostUsedShowsTopAppsThenOther() {
        val totals = (1..7).associate { "app$it" to it * 10 }
        val s = seriesFor(emptyList(), totals) { it }
        assertEquals(listOf("app:app7", "app:app6", "app:app5", "app:app4", "app:app3", "other"), s.map { it.key })
        assertEquals(setOf("app1", "app2"), s.last().apps)
    }

    @Test
    fun choosingAppsShowsExactlyThoseInOrder() {
        val s = seriesFor(listOf("app:game2", "app:yt"), emptyMap()) { it }
        assertEquals(listOf("app:game2", "app:yt"), s.map { it.key })
        assertEquals(45, minutesOn(days, LocalDate.parse("2026-11-10"), s[1]))
    }

    @Test
    fun shiftingAMonthKeepsItValid() {
        assertEquals(LocalDate.parse("2026-02-28"), shift(Period.MONTH, LocalDate.parse("2026-01-31"), 1))
    }

    @Test
    fun hidingSystemAppsKeepsOnlyHerAppsAndEveryDay() {
        val kept = onlyApps(days, setOf("yt"))
        assertEquals(setOf("2026-11-09", "2026-11-10", "2026-11-30"), kept.keys)
        assertEquals(mapOf("yt" to 30), kept["2026-11-09"])
    }
}
