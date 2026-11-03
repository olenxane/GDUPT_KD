package com.aggapp.app.ui

import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualWaybillsTest {

    @Test
    fun normalize_stripsSpacesDashesAndUppercases() {
        assertEquals("SF1234567890123", ManualWaybills.normalize(" sf 1234-5678 90123 "))
        assertEquals("YT1234567890123", ManualWaybills.normalize("yt1234567890123"))
    }

    @Test
    fun normalize_rejectsInvalid() {
        assertNull(ManualWaybills.normalize(""))
        assertNull(ManualWaybills.normalize("1234567"))
        assertNull(ManualWaybills.normalize("SF12345678|99"))
        assertNull(ManualWaybills.normalize("A".repeat(31)))
    }

    @Test
    fun encodeDecode_roundTrip() {
        val w = ManualWaybill("78123456789012", 1_700_000_000_000)
        assertEquals(w, ManualWaybills.decode(ManualWaybills.encode(w)))
        assertNull(ManualWaybills.decode("no-separator"))
        assertNull(ManualWaybills.decode("ABC|notanumber"))
    }

    private fun item(id: String, no: String?, code: String? = null, station: String = "驿站", p: Platform = Platform.TAOBAO) =
        ExpressItem(id = id, platform = p, station = station, trackingNo = no, pickupCode = code)

    @Test
    fun dedupe_keepsItemsWithoutTrackingNo() {
        val out = ExpressDedupe.dedupe(
            listOf(item("a", null, "1-1-1"), item("b", "", "2-2-2"), item("c", "N1", "3-3-3")),
        )
        assertEquals(setOf("a", "b", "c"), out.map { it.id }.toSet())
    }

    @Test
    fun dedupe_mergesSameTrackingNo_preferringCode() {
        val out = ExpressDedupe.dedupe(
            listOf(
                item("pdd", "N1", null, station = "", p = Platform.PDD).copy(summary = "耳机", arrivedAt = 200),
                item("tb", "N1", "5-2-3001", station = "菜鸟驿站").copy(arrivedAt = 100),
            ),
        )
        assertEquals(1, out.size)
        val m = out.single()
        assertEquals("5-2-3001", m.pickupCode)
        assertEquals("菜鸟驿站", m.station)
        assertEquals("耳机", m.summary)
        assertEquals(100L, m.arrivedAt)
    }

    @Test
    fun dedupe_mainSentinelSummary_fallsBackToGoodsName() {
        val out = ExpressDedupe.dedupe(
            listOf(
                item("tb", "N1", "86-4-2008", station = "菜鸟驿站").copy(summary = "运单号添加"),
                item("pdd", "N1", null, p = Platform.PDD).copy(summary = "阿华田夹心华夫饼340g"),
            ),
        )
        assertEquals(1, out.size)
        val m = out.single()
        assertEquals("86-4-2008", m.pickupCode)      // 主体的真取件码保留
        assertEquals("阿华田夹心华夫饼340g", m.summary) // 哨兵值被另一方的商品名顶掉
    }

    @Test
    fun dedupe_bothSentinelSummary_staysNull() {
        val out = ExpressDedupe.dedupe(
            listOf(
                item("tb", "N1", "61-2-2010").copy(summary = "运单号添加"),
                item("pdd", "N1", null, p = Platform.PDD).copy(summary = "运单号添加"),
            ),
        )
        assertNull(out.single().summary)
    }

    @Test
    fun dedupe_blankCodeDoesNotHideRealCode() {
        val out = ExpressDedupe.dedupe(
            listOf(
                item("sf", "N1", "", station = "顺丰驿站", p = Platform.SF),
                item("tb", "N1", "5-2-3001", station = "菜鸟驿站"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("5-2-3001", out.single().pickupCode)
    }

    @Test
    fun normalizer_treatsWaybillEchoAsNoCode() {
        assertNull(PickupCodeNormalizer.normalize("78123456789012", "78123456789012"))
        assertNull(PickupCodeNormalizer.normalize("78123456789012"))
        assertEquals("5-2-3001", PickupCodeNormalizer.normalize("5-2-3001")?.code)
        val tail = PickupCodeNormalizer.normalize("取件出示单号后五位79302")
        assertEquals("79302", tail?.code)
        assertEquals("出示单号后5位", tail?.hint)
    }
}
