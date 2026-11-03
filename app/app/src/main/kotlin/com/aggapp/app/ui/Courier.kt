package com.aggapp.app.ui

/**
 * 运单号前缀识别（仅保留无歧义的前缀规则）：
 * 纯数字号段（75~79 开头等）中通/申通/极兔互有重叠，正则无法可靠区分，
 * 因此不再按公司路由——补码一律由「取件码形态」（订单尾号码）驱动，见 PickupCodeNormalizer。
 */
enum class CourierCompany(val label: String) {
    SF("顺丰"),
    YTO("圆通"),
    OTHER("其他快递");

    companion object {
        private val SF_NO = Regex("^SF\\d{12,15}$") // 顺丰
        private val YTO_NO = Regex("^YT\\d{13}$")   // 圆通

        /** 按单号识别；空串返回 null，识别不出归 OTHER */
        fun of(no: String?): CourierCompany? {
            val s = no?.trim()?.uppercase() ?: return null
            if (s.isEmpty()) return null
            return when {
                SF_NO.matches(s) -> SF
                YTO_NO.matches(s) -> YTO
                else -> OTHER
            }
        }
    }
}

/** 归一化后的取件码：纯码 + 展示提示（如「出示单号后五位」） */
data class NormalizedCode(val code: String, val hint: String? = null)

/**
 * 平台返回的"取件码"字段脏数据归一化：
 * - 菜鸟格式（1-2-3001）原样；
 * - 描述文本（拼多多常见，如「取件出示单号后五位79302」）提取末尾数字，hint 标注出示方式；
 * - 运单号回填（等于运单号、或 11 位以上纯数字长串）视为无码 → 返回 null（触发菜鸟补码）；
 * - 短数字/含字母短码原样。
 */
object PickupCodeNormalizer {
    private val CAINIAO_CODE = Regex("^\\d{1,4}-\\d{1,2}-\\d{1,4}$")
    private val TAIL_HINT = Regex("(单号)?后(\\d|[一二三四五六七八九十])位")
    private val DIGITS = Regex("\\d+")

    /** 菜鸟驿站标准取件码格式（形如 1-2-3001） */
    fun isCainiaoStyle(code: String?): Boolean =
        code != null && CAINIAO_CODE.matches(code.trim())

    fun normalize(raw: String?, waybill: String? = null): NormalizedCode? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (waybill != null && s == waybill.trim().uppercase()) return null
        if (CAINIAO_CODE.matches(s)) return NormalizedCode(s)
        // 须在描述文本分支前判断，否则长度 >10 的运单号会被当作码返回
        if (s.length >= 11 && s.all { it.isDigit() }) return null
        // 描述文本型：提取全部数字段，取最长/末段为码
        if (TAIL_HINT.containsMatchIn(s) || s.length > 10) {
            val nums = DIGITS.findAll(s).map { it.value }.toList()
            val code = nums.lastOrNull { it.length in 3..8 } ?: nums.lastOrNull() ?: return null
            if (waybill != null && code == waybill.trim()) return null
            val hint = if (TAIL_HINT.containsMatchIn(s)) "出示单号后${code.length}位" else null
            return NormalizedCode(code, hint)
        }
        return NormalizedCode(s)
    }
}
