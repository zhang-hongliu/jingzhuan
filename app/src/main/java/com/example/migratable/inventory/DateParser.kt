package com.example.migratable.inventory

import java.util.Calendar

/**
 * 从 OCR 文字 / 条码内容中解析过期日期。
 * 支持：2026-08-01、2026/08/01、2026.08.01、2026年8月1日、20260801、
 * GS1 条码 AI(17) 到期日字段（(17)YYMMDD 或 17YYMMDD）。
 * 一段文字里有多个日期时取最晚的（生产日期通常早于到期日期）。
 */
object DateParser {

    private val FULL_DATE = Regex("""(20\d{2})[\s]*[-/.年][\s]*(\d{1,2})[\s]*[-/.月][\s]*(\d{1,2})日?""")
    private val COMPACT_DATE = Regex("""(?<!\d)(20\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])(?!\d)""")
    private val GS1_EXPIRY = Regex("""\(?17\)?(\d{2})(0[1-9]|1[0-2])(\d{2})""")

    /** 返回当天 00:00 的 epoch millis，解析失败返回 null */
    fun parse(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val candidates = mutableListOf<Long>()

        for (m in FULL_DATE.findAll(text)) {
            toMillis(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?.let { candidates.add(it) }
        }
        for (m in COMPACT_DATE.findAll(text)) {
            toMillis(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                ?.let { candidates.add(it) }
        }
        // GS1: 仅当没解析到普通日期时才尝试（避免把普通数字误判）
        if (candidates.isEmpty()) {
            for (m in GS1_EXPIRY.findAll(text)) {
                val yy = 2000 + m.groupValues[1].toInt()
                var day = m.groupValues[3].toInt()
                if (day == 0) day = lastDayOf(yy, m.groupValues[2].toInt()) // GS1 允许 DD=00 表示月末
                toMillis(yy, m.groupValues[2].toInt(), day)?.let { candidates.add(it) }
            }
        }
        return candidates.maxOrNull()
    }

    fun toMillis(year: Int, month: Int, day: Int): Long? {
        if (month !in 1..12 || day !in 1..31) return null
        return try {
            Calendar.getInstance().apply {
                isLenient = false
                clear()
                set(year, month - 1, day)
            }.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /** 今天 00:00 的 epoch millis */
    fun todayMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun format(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 距离过期还有几天：负数=已过期 N 天 */
    fun daysLeft(expiryAt: Long): Int =
        ((expiryAt - todayMillis()) / 86_400_000L).toInt()

    private fun lastDayOf(year: Int, month: Int): Int =
        Calendar.getInstance().apply {
            clear(); set(year, month - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
}
