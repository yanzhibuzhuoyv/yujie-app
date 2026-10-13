package com.cqmu.assistant.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 两种本地缓存的对齐策略（均为纯函数，便于测试）：
 * - 课表：以「周」为锚点。最近一个周日（含今天若是周日）之前同步过就视为新鲜，
 *   跨过一个周日才在后台重新同步一次，防止调课；
 * - 空教室：以「每日凌晨 5:00」为锚点，取回时间晚于最近一次 5 点即视为新鲜。
 */
object SyncPolicy {

    /** 最近一个周日（含今天若是周日）的 epoch day */
    fun weeklyAnchorEpochDay(today: LocalDate): Long =
        today.minusDays((today.dayOfWeek.value % 7).toLong()).toEpochDay()

    /**
     * @param lastSyncEpochDay 上次同步日期；<=0 表示从未同步
     * @return true = 需要后台重新同步
     */
    fun needsWeeklySync(lastSyncEpochDay: Long, today: LocalDate = LocalDate.now()): Boolean {
        if (lastSyncEpochDay <= 0L) return true
        return lastSyncEpochDay < weeklyAnchorEpochDay(today)
    }

    /** 取回时间是否晚于最近一次凌晨 5:00（今天 5 点前打开则锚点是昨天 5 点） */
    fun freshSince5AM(
        fetchedEpochMillis: Long,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        val anchor = if (now.hour >= 5) now.toLocalDate().atTime(5, 0)
        else now.toLocalDate().minusDays(1).atTime(5, 0)
        val anchorMillis = anchor.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return fetchedEpochMillis >= anchorMillis
    }

    /**
     * 每日 5 点锚点（epoch day 口径，与课表的周锚点同一套比较方式）：
     * 今天已过 5 点 → 锚点是今天；还没到 5 点 → 锚点是昨天。
     * 上次同步早于锚点日期 = 跨过了一次凌晨 5 点 → 需要重新同步。
     */
    fun needsDailySync(lastSyncEpochDay: Long, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (lastSyncEpochDay <= 0L) return true
        val anchorDate = if (now.hour >= 5) now.toLocalDate()
        else now.toLocalDate().minusDays(1)
        return lastSyncEpochDay < anchorDate.toEpochDay()
    }
}
