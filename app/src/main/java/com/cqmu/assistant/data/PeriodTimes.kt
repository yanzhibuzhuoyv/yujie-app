package com.cqmu.assistant.data

/**
 * 节次 → 上课时间（按学校实际作息推算）：
 * - 第 1 节 08:30 上课；第 2、3 节课后休息 10 分钟，其余课间 5 分钟
 * - 第 8 节 14:00 上课；第 9、10 节课后休息 10 分钟，其余 5 分钟
 * - 第 15 节 19:00 上课；15-18 节课后均休息 5 分钟
 * - 每节课 40 分钟
 */
object PeriodTimes {
    private const val CLASS_MIN = 40

    private fun blockStart(period: Int): Int = when (period) {
        in 1..7 -> 1
        in 8..14 -> 8
        else -> 15
    }

    private fun anchorMinutes(blockStart: Int): Int = when (blockStart) {
        1 -> 8 * 60 + 30   // 08:30
        8 -> 14 * 60       // 14:00
        else -> 19 * 60    // 19:00
    }

    private fun breakAfter(period: Int): Int = when (period) {
        2, 3, 9, 10 -> 10
        else -> 5
    }

    /** 第 period 节的开始时刻（当天分钟数） */
    fun startMinutes(period: Int): Int {
        val bs = blockStart(period)
        var t = anchorMinutes(bs)
        for (p in bs until period) t += CLASS_MIN + breakAfter(p)
        return t
    }

    fun endMinutes(period: Int): Int = startMinutes(period) + CLASS_MIN

    fun start(period: Int): String? = fmt(startMinutes(period))

    /** "HH:MM-HH:MM" */
    fun range(period: Int): String = "${fmt(startMinutes(period))}-${fmt(endMinutes(period))}"

    private fun fmt(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}
