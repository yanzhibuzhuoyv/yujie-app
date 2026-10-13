package com.cqmu.assistant.data

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CurrentPeriodTarget(
    val date: LocalDate,
    val period: Int
)

/**
 * 将校园当前时间转成各功能页可使用的索引。
 *
 * 这里保持为纯 Kotlin 逻辑：UI 和 ViewModel 只消费结果，测试可以传入
 * 固定时钟，不依赖设备所在时区。
 */
object CurrentTimeNavigation {
    val campusZone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun campusNow(clock: Clock = Clock.systemUTC()): LocalDateTime =
        LocalDateTime.ofInstant(clock.instant(), campusZone)

    /**
     * 上课中返回当前小节；课间/午休返回下一小节；晚课结束后返回 null。
     * 作息表的分段锚点可能产生边界重叠，此时以开始更晚的小节为准。
     */
    fun periodAtOrNext(time: LocalTime): Int? {
        val minute = time.hour * 60 + time.minute
        val periods = (1..18).map { period ->
            PeriodWindow(period, PeriodTimes.startMinutes(period), PeriodTimes.endMinutes(period))
        }
        periods
            .filter { minute >= it.startMinute && minute < it.endMinute }
            .maxByOrNull { it.startMinute }
            ?.let { return it.period }
        return periods.firstOrNull { it.startMinute > minute }?.period
    }

    /** 晚课结束后的“现在查询”自动转到次日第 1 节。 */
    fun freeRoomTarget(now: LocalDateTime): CurrentPeriodTarget {
        val period = periodAtOrNext(now.toLocalTime())
        return if (period == null) CurrentPeriodTarget(now.toLocalDate().plusDays(1), 1)
        else CurrentPeriodTarget(now.toLocalDate(), period)
    }

    fun semesterWeek(
        week1Monday: LocalDate,
        date: LocalDate,
        maxWeeks: Int = 18
    ): Int? {
        val days = ChronoUnit.DAYS.between(week1Monday, date)
        val week = (days / 7).toInt() + 1
        return week.takeIf { days >= 0 && it in 1..maxWeeks }
    }

    private data class PeriodWindow(
        val period: Int,
        val startMinute: Int,
        val endMinute: Int
    )
}
