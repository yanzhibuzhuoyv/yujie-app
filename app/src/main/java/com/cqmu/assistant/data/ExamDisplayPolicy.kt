package com.cqmu.assistant.data

import java.time.LocalDate

/** v1.4.2 的考试展示规则：只有考试日期早于今天时才标记为已考完。 */
object ExamDisplayPolicy {
    fun isFinished(dateText: String, today: LocalDate = LocalDate.now()): Boolean =
        runCatching { LocalDate.parse(dateText.trim()).isBefore(today) }.getOrDefault(false)
}
