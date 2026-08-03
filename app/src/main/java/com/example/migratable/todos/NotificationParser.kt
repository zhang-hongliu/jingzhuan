package com.example.migratable.todos

import com.example.migratable.inventory.DateParser

/**
 * 从手机通知中识别两类待办：
 * 1) 快递取件码（EXPRESS）：菜鸟 / 丰巢 / 各快递公司的取件码、取货码等
 * 2) 普通待办（TODO）：含待办/提醒/到期/缴费/还款/会议 等关键词，或「时间+动作」句式
 *
 * 解析结果不含副作用，便于单测与复用。
 */
object NotificationParser {

    // 取件码类：取件码 / 取货码 / 凭码 / 提货码 / 自提码 / 取件编号 / 快递码 / 取件口令
    private val EXPRESS_CODE = Regex("""(?:取件码|取货码|凭码|提货码|自提码|取件编号|快递码|取件口令)[\s:：为是]*([0-9][0-9\-]{2,15})""")

    // 常见快递 / 驿站品牌
    private val COURIER = Regex("""(菜鸟驿站|菜鸟|丰巢|中通|圆通|申通|韵达|顺丰|京东|邮政|EMS|德邦|极兔|百世|天猫|苏宁|美团|拼多多)""")

    private val TODO_KEYWORDS = listOf(
        "待办", "待办事项", "提醒", "记得", "别忘了", "别忘记", "截止", "截至",
        "到期", "到期日", "缴费", "还款", "信用卡还款", "账单", "续费", "缴纳",
        "会议", "预约", "面试", "复诊", "办理", "领取", "签到", "打卡", "提交",
        "报名", "待支付", "待付款", "待确认", "待处理", "催缴", "房贷", "车贷",
        "税费", "水费", "电费", "燃气费", "物业费", "话费", "宽带费", "年检", "续保"
    )

    // 时间 + 动作 句式，例如「明天 10:00 开会」「8月1日 提交材料」
    private val TIME_ACTION = Regex(
        "(今天|明天|后天|周[一二三四五六日天]|星期[一二三四五六日天]|[0-9]{1,2}月[0-9]{1,2}[日号]?|[0-9]{1,2}[:：][0-9]{2}|20[0-9]{2}[-/.年][0-9]{1,2}[-/.月][0-9]{1,2}[日号]?)\\s*[^，。；;\\n]{0,14}(会议|还款|缴费|提交|办理|面试|签到|打卡|领取|预约|到期|截止|提醒|交|还|报|办|处理|支付)"
    )

    fun parse(packageName: String?, title: String?, text: String?): List<ParsedTodo> {
        val t = title?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        if (t.isBlank() && body.isBlank()) return emptyList()
        val full = "$t\n$body"
        val result = mutableListOf<ParsedTodo>()

        // 1) 快递取件码优先
        val codeMatch = EXPRESS_CODE.find(full)
        if (codeMatch != null) {
            val code = codeMatch.groupValues[1]
            val courier = COURIER.find(full)?.groupValues?.get(1)
            result += ParsedTodo(
                type = "EXPRESS",
                title = (courier?.let { "$it·" } ?: "快递·") + "取件码 $code",
                content = sentenceAround(full, codeMatch.range.first) ?: full,
                code = code,
                courier = courier,
                dueAt = DateParser.parse(full)
            )
            return result // 快递通知不再额外建待办，避免重复
        }

        // 2) 普通待办：关键词 或 时间+动作 句式
        val kw = TODO_KEYWORDS.firstOrNull { full.contains(it) }
        val timeAction = TIME_ACTION.find(full)
        if (kw != null || timeAction != null) {
            val titleText = if (t.isNotBlank()) t else firstLine(body)
            val content = if (kw != null) {
                sentenceAround(full, full.indexOf(kw)) ?: firstLine(body)
            } else {
                timeAction?.value ?: firstLine(body)
            }
            result += ParsedTodo(
                type = "TODO",
                title = titleText.take(40),
                content = content.take(200),
                dueAt = DateParser.parse(full)
            )
        }
        return result
    }

    private fun firstLine(text: String): String =
        text.lines().firstOrNull { it.isNotBlank() } ?: text

    /** 取 index 所在的那「句」（以换行或中英文标点切分） */
    private fun sentenceAround(text: String, index: Int): String? {
        if (index < 0 || index >= text.length) return null
        val seps = charArrayOf('\n', '。', '；', ';', '，', ',')
        var s = text.lastIndexOfAny(seps, index)
        var e = text.indexOfAny(seps, index)
        s = if (s < 0) 0 else s + 1
        e = if (e < 0) text.length else e
        return text.substring(s, e).trim().takeIf { it.isNotBlank() }
    }
}

data class ParsedTodo(
    val type: String,
    val title: String,
    val content: String,
    val code: String? = null,
    val courier: String? = null,
    val dueAt: Long? = null
)

/** 解析结果转持久化实体（顶层扩展，便于跨文件直接调用） */
fun ParsedTodo.toEntity(sourceApp: String?, sourceTitle: String?) = TodoEntity(
    type = type,
    title = title,
    content = content,
    code = code,
    sourceApp = sourceApp,
    sourceTitle = sourceTitle,
    rawText = null,
    dueAt = dueAt,
    done = false
)
