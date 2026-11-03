package com.aggapp.app.ui

import com.aggapp.core.model.ExpressItem

/** 用户手动添加的运单号，addedAt 用于排序展示 */
data class ManualWaybill(val no: String, val addedAt: Long)

object ManualWaybills {
    private val WAYBILL = Regex("^[A-Z0-9]{8,30}$")

    /** 去空白转大写后校验，非法返回 null */
    fun normalize(raw: String): String? {
        val s = raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        return s.takeIf { WAYBILL.matches(it) }
    }

    fun encode(w: ManualWaybill): String = "${w.no}|${w.addedAt}"

    fun decode(s: String): ManualWaybill? {
        val i = s.lastIndexOf('|')
        if (i <= 0) return null
        val no = s.substring(0, i)
        val at = s.substring(i + 1).toLongOrNull() ?: return null
        return ManualWaybill(no, at)
    }
}

/** 跨平台同运单号合并；无运单号的条目（如顺丰校园接口不回单号）原样保留 */
object ExpressDedupe {
    fun dedupe(items: List<ExpressItem>): List<ExpressItem> {
        val byNo = linkedMapOf<String, ExpressItem>()
        val noTracking = mutableListOf<ExpressItem>()
        for (item in items) {
            val no = item.trackingNo
            if (no.isNullOrBlank()) {
                noTracking += item
                continue
            }
            val exist = byNo[no]
            byNo[no] = if (exist == null) item else merge(exist, item)
        }
        return byNo.values.toList() + noTracking
    }

    /** 有取件码者为主体，其余字段互补，入仓时间取更早者 */
    fun merge(a: ExpressItem, b: ExpressItem): ExpressItem {
        // 空串（顺丰接口常见 "pickupCode":""）等同于无码，否则会压掉另一平台的真实取件码
        val aCode = a.pickupCode?.takeIf { it.isNotBlank() }
        val bCode = b.pickupCode?.takeIf { it.isNotBlank() }
        val main = if (aCode != null) a else if (bCode != null) b else a
        val other = if (main === a) b else a
        return main.copy(
            station = main.station.ifBlank { other.station },
            stationAddress = main.stationAddress ?: other.stationAddress,
            pickupCode = aCode ?: bCode,
            pickupCodeSource = main.pickupCodeSource ?: other.pickupCodeSource,
            // summary 跳过哨兵值：主体是"运单号添加"这类标记时回退另一方的真商品名
            summary = realSummary(main) ?: realSummary(other),
            arrivedAt = listOfNotNull(main.arrivedAt, other.arrivedAt).minOrNull(),
        )
    }

    private fun realSummary(item: ExpressItem): String? =
        item.summary?.takeIf { it.isNotBlank() && it !in ExpressItem.SUMMARY_SENTINELS }
}
