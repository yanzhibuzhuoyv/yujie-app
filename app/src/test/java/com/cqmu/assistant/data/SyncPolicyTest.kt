package com.cqmu.assistant.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SyncPolicyTest {

    // 周锚点：最近一个周日（含今天若是周日）

    @Test
    fun `weekly anchor is the most recent sunday`() {
        // 2026-08-29 是周六 → 锚点是刚过去的周日 2026-08-23
        val sat = LocalDate.of(2026, 8, 29)
        val prevSun = LocalDate.of(2026, 8, 23)
        val sun = LocalDate.of(2026, 8, 30)
        assertTrue(SyncPolicy.weeklyAnchorEpochDay(sat) == prevSun.toEpochDay())
        // 周日当天锚点就是自己
        assertTrue(SyncPolicy.weeklyAnchorEpochDay(sun) == sun.toEpochDay())
    }

    @Test
    fun `weekly sync triggers only after crossing a sunday`() {
        val sat = LocalDate.of(2026, 8, 29)   // 周六
        val sun = LocalDate.of(2026, 8, 30)   // 周日
        val mon = LocalDate.of(2026, 8, 31)   // 周一
        val nextSun = LocalDate.of(2026, 9, 6)

        assertTrue("从未同步必须同步", SyncPolicy.needsWeeklySync(0L, sat))
        assertTrue("周六同步、周一打开：跨过了周日，需要对齐",
            SyncPolicy.needsWeeklySync(sat.toEpochDay(), mon))
        assertFalse("周日当天已同步、周三打开：未跨周日，不重复请求",
            SyncPolicy.needsWeeklySync(sun.toEpochDay(), LocalDate.of(2026, 9, 2)))
        assertTrue("周一同步、下个周日打开：跨过新周日，需要对齐",
            SyncPolicy.needsWeeklySync(mon.toEpochDay(), nextSun))
        assertFalse("周日当天刚同步过：不再同步",
            SyncPolicy.needsWeeklySync(nextSun.toEpochDay(), nextSun))
    }

    // 5 点锚点：最近一次凌晨 5:00 之后取回即新鲜

    @Test
    fun `daily 5am anchor freshness`() {
        val nowMorning = LocalDateTime.of(2026, 9, 1, 10, 0)
        val fetched6am = LocalDateTime.of(2026, 9, 1, 6, 0).atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val fetchedYesterdayNight = LocalDateTime.of(2026, 8, 31, 23, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue("今天 6 点取回、10 点打开：新鲜",
            SyncPolicy.freshSince5AM(fetched6am, nowMorning))
        assertFalse("昨晚 23 点取回、今天 10 点打开：已跨 5 点锚点，过期",
            SyncPolicy.freshSince5AM(fetchedYesterdayNight, nowMorning))

        val nowBefore5 = LocalDateTime.of(2026, 9, 1, 3, 0)
        val fetchedYesterday8pm = LocalDateTime.of(2026, 8, 31, 20, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fetchedYesterday4am = LocalDateTime.of(2026, 8, 31, 4, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue("凌晨 3 点打开：昨天 5 点后取回的仍新鲜",
            SyncPolicy.freshSince5AM(fetchedYesterday8pm, nowBefore5))
        assertFalse("昨天 4 点取回的早于昨天 5 点锚点：过期",
            SyncPolicy.freshSince5AM(fetchedYesterday4am, nowBefore5))
    }
}
