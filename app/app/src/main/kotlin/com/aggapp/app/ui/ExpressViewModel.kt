package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.platform.api.AppLog
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 快递页 UI 状态 */
data class ExpressUiState(
    val loading: Boolean = false,
    val loggedInPlatforms: Set<Platform> = emptySet(),
    val stations: List<StationGroup> = emptyList(),   // 按驿站分组（顺丰+淘宝合并）
    val error: String? = null,
    // 运单号查询
    val searching: Boolean = false,
    val searchNotice: String? = null,                 // 查不到/未接入提示
    val searchResult: PickupCode? = null,             // 查到的取件码
    val needSuffixFor: String? = null,                // 顺丰单号不在列表 → 需输入手机后4位
    val manualNos: Set<String> = emptySet(),          // 手动添加的运单号，卡片上可删除
    val manualAdding: Boolean = false,
)

/** 驿站分组 */
data class StationGroup(
    val stationName: String,
    val items: List<ExpressItem>,
)

/**
 * 快递页：
 * - 列表：各平台包裹按驿站分组；已签收/已取出不显示
 * - 跟踪单号：搜索查到的单自动加入跟踪（SharedPreferences 持久化），每次刷新
 *   按来源平台单独查状态；签收/查无自动移除
 */
@HiltViewModel
class ExpressViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val sessionRepo: SessionRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val adapters = registry.adapters
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tracked = MutableStateFlow(loadTracked())
    private val _manual = MutableStateFlow(loadManual())

    private val _ui = MutableStateFlow(ExpressUiState(manualNos = _manual.value.keys))
    val ui: StateFlow<ExpressUiState> = _ui.asStateFlow()

    /** 同一时刻只跑一次刷新，新刷新取消旧的，避免两个任务交错写列表与跟踪表 */
    private var refreshJob: kotlinx.coroutines.Job? = null

    init {
        // 兜底：万一别的入口还没恢复凭据（正常 AggApp.onCreate 已 restore，幂等）
        if (sessionRepo.credentials.value.isEmpty()) sessionRepo.restore()
        refresh(force = true)
        // 凭据迟到（登录成功/异步恢复完成）时自动补一次刷新，无需手动切「我的」页
        viewModelScope.launch {
            var wasEmpty = sessionRepo.credentials.value.isEmpty()
            sessionRepo.credentials.collect { creds ->
                val nowEmpty = creds.isEmpty()
                if (wasEmpty && !nowEmpty && _ui.value.stations.isEmpty()) refresh()
                wasEmpty = nowEmpty
            }
        }
    }

    /** 上次刷新时间，进入页面自动刷新用 30s 节流 */
    private var lastRefreshAt = 0L

    /** 清除查询结果展示（点空白处/刷新时调用；结果单号已加入跟踪列表，不受影响） */
    fun clearSearchResult() {
        _ui.value = _ui.value.copy(searchResult = null, searchNotice = null)
    }

    /**
     * 拉取各平台包裹列表 + 跟踪单状态合并（顺丰+淘宝+拼多多，各自独立容错）
     * @param force 用户主动刷新用 true：强制执行并显示 loading；
     *              进入页面自动刷新用默认 false：30s 节流，已有数据时静默刷新（不闪 loading，
     *              全部失败保留旧列表不弹错）
     */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now
        val silent = !force && _ui.value.stations.isNotEmpty()
        val creds = sessionRepo.credentials.value
        val loggedIn = creds.keys.filter {
            it == Platform.SF || it == Platform.TAOBAO || it == Platform.PDD
        }.toSet()
        if (loggedIn.isEmpty()) {
            refreshJob?.cancel()
            _ui.value = ExpressUiState(
                error = "请先在「我的」页登录顺丰/淘宝（短信验证码）/拼多多",
                manualNos = _manual.value.keys,
            )
            return
        }
        _ui.value = _ui.value.copy(loading = !silent, error = null, loggedInPlatforms = loggedIn, searchResult = null, searchNotice = null)
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val errors = mutableListOf<String>()
            val loadedPlatforms = mutableSetOf<Platform>()
            val allItems = mutableListOf<ExpressItem>()
            val byNo = mutableMapOf<String, ExpressItem>()
            for (platform in listOf(Platform.SF, Platform.TAOBAO, Platform.PDD)) {
                val cred = creds[platform] ?: continue
                try {
                    val items = mutableListOf<ExpressItem>()
                    for (page in 1..MAX_PAGES) {
                        val batch = adapters.getValue(platform).fetchExpressList(cred, page)
                        items += batch
                        if (batch.size < PAGE_SIZE) break
                    }
                    for (item in items) {
                        val no = item.trackingNo
                        if (no != null) byNo[no] = item // 全量索引（含签收，供跟踪状态判断）
                    }
                    allItems += items
                    loadedPlatforms += platform
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${platformLabel(platform)}：${e.message}"
                }
            }

            // ① 跨平台运单号去重：同一单多平台各有一份 → 合并为一条（有码优先、字段互补）
            val deduped = ExpressDedupe.dedupe(allItems.filter { it.status == ExpressStatus.WAIT_PICKUP })
            // ② 无码待取件：用运单号调其他平台查件接口互补补码；查不到的原样展示
            val completed = backfillPickupCodes(deduped, creds)
            // ③ 按驿站分组
            val groups = linkedMapOf<String, MutableList<ExpressItem>>()
            fun add(item: ExpressItem) {
                val name = item.station.ifBlank { "未知驿站" }
                groups.getOrPut(name) { mutableListOf() } += item
            }
            for (item in completed) add(item)

            // ④ 跟踪单：列表没有的按来源平台单独查状态；签收/查无 → 移除跟踪
            // PDD 列表查询限频（54001）：多个跟踪单共享一次拉取结果
            var pddListCache: List<ExpressItem>? = null
            // 只记录“需要移除”的增量：刷新过程中用户可能新加跟踪单，直接写回旧快照会把新单覆盖掉
            val trackedRemoved = mutableSetOf<String>()
            for ((no, plat) in _tracked.value.toList()) {
                val inList = byNo[no]
                if (inList != null && inList.status == ExpressStatus.PICKED) {
                    trackedRemoved += no
                    continue
                }
                if (inList != null) continue // 列表已有且未签收
                val cred = creds[plat] ?: continue // 来源平台未登录：保留跟踪不误删
                try {
                    when (plat) {
                        Platform.PDD -> {
                            // 快递助手列表一次拿状态（含取件码），多跟踪单复用缓存
                            val list = pddListCache
                                ?: adapters.getValue(Platform.PDD).fetchExpressList(cred, 1).also { pddListCache = it }
                            val it2 = list.firstOrNull { it.trackingNo == no }
                            when {
                                it2 == null || it2.status == ExpressStatus.PICKED -> trackedRemoved += no
                                else -> add(it2)
                            }
                        }
                        else -> {
                            val code = adapters.getValue(plat).fetchPickupCode(cred, no) ?: continue
                            add(
                                ExpressItem(
                                    id = no,
                                    platform = plat,
                                    station = "跟踪单号",
                                    stationAddress = null,
                                    trackingNo = no,
                                    pickupCode = code.code,
                                    summary = null,
                                    status = ExpressStatus.WAIT_PICKUP,
                                    arrivedAt = null,
                                    fetchDeadline = null,
                                    raw = null,
                                ),
                            )
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.w("Express", "track $no: ${platformLabel(plat)} 状态查询失败，保留跟踪", e)
                }
            }
            if (trackedRemoved.isNotEmpty()) {
                val updated = _tracked.value - trackedRemoved
                if (updated != _tracked.value) {
                    _tracked.value = updated
                    saveTracked(updated)
                }
            }

            // ⑤ 手动添加的单号：列表/跟踪未覆盖的逐个查码；已签收自动移除，查不到也保留展示
            val shown = groups.values.flatten().mapNotNull { it.trackingNo }.toMutableSet()
            val manualRemoved = mutableSetOf<String>()
            for (no in _manual.value.keys.toList()) {
                val inList = byNo[no]
                if (inList?.status == ExpressStatus.PICKED) {
                    manualRemoved += no
                    continue
                }
                if (no in shown) continue
                add(resolveManual(no, creds))
                shown += no
            }
            if (manualRemoved.isNotEmpty()) {
                val updated = _manual.value - manualRemoved
                if (updated != _manual.value) {
                    _manual.value = updated
                    saveManual(updated)
                }
            }
            _ui.value = _ui.value.copy(
                loading = false,
                // 自动移除已签收的手动单号后，删除按钮的展示集合也要同步
                manualNos = _manual.value.keys,
                // 静默刷新全部失败时保留旧列表，不打扰用户（下次进入或手动刷新再试）
                // 手动分组总能产出条目，所以按「是否有平台成功」判断，而非 groups 是否为空
                stations = if (silent && loadedPlatforms.isEmpty() && errors.isNotEmpty()) {
                    _ui.value.stations
                } else {
                    groups.map { (name, list) -> StationGroup(name, list.toList()) }
                },
                error = if (silent) {
                    null // 静默刷新不弹错误（旧数据仍在展示）
                } else if (groups.isEmpty() && errors.isNotEmpty()) errors.joinToString("\n") else null,
            )
        }
    }

    /**
     * 手动添加运单号：校验后持久化，立即查一次码并插入「手动添加」分组；
     * 之后每次刷新自动重查，平台报已签收时移除。
     */
    fun addManualWaybill(raw: String) {
        val no = ManualWaybills.normalize(raw)
        if (no == null) {
            _ui.value = _ui.value.copy(searchNotice = "运单号格式不正确（8-30 位字母或数字）")
            return
        }
        if (_manual.value.containsKey(no)) {
            _ui.value = _ui.value.copy(searchNotice = "$no 已在手动添加列表中")
            return
        }
        if (_manual.value.size >= MAX_MANUAL) {
            _ui.value = _ui.value.copy(searchNotice = "手动添加最多 $MAX_MANUAL 个单号，请先删除不需要的")
            return
        }
        val m = _manual.value + (no to System.currentTimeMillis())
        _manual.value = m
        saveManual(m)
        _ui.value = _ui.value.copy(manualNos = m.keys, manualAdding = true, searchNotice = null)
        viewModelScope.launch {
            val creds = sessionRepo.credentials.value
            val shown = _ui.value.stations.any { g -> g.items.any { it.trackingNo == no } }
            val item = if (shown) null else resolveManual(no, creds)
            val stations = if (item == null) {
                _ui.value.stations
            } else {
                val name = item.station
                val existing = _ui.value.stations.firstOrNull { it.stationName == name }
                if (existing != null) {
                    _ui.value.stations.map { if (it === existing) it.copy(items = it.items + item) else it }
                } else {
                    _ui.value.stations + StationGroup(name, listOf(item))
                }
            }
            _ui.value = _ui.value.copy(
                manualAdding = false,
                stations = stations,
                error = if (stations.isNotEmpty()) null else _ui.value.error,
                searchNotice = when {
                    item == null -> "$no 已在列表中，已加入手动跟踪"
                    item.pickupCode != null -> "已添加 $no，取件码 ${item.pickupCode}"
                    creds.isEmpty() -> "已添加 $no；登录任一平台后可自动查取件码"
                    else -> "已添加 $no；暂未查到取件码，刷新时会自动重查"
                },
            )
        }
    }

    /** 删除手动添加的单号（只移出手动列表；平台列表自带的同号包裹不受影响） */
    fun removeManualWaybill(no: String) {
        val m = _manual.value - no
        if (m == _manual.value) return
        _manual.value = m
        saveManual(m)
        _ui.value = _ui.value.copy(
            manualNos = m.keys,
            stations = _ui.value.stations
                .map { g -> g.copy(items = g.items.filterNot { it.trackingNo == no && it.station == MANUAL_STATION }) }
                .filter { it.items.isNotEmpty() },
        )
    }

    /** 手动单号查码：按「上次命中渠道 → 单号前缀对应平台 → FALLBACK_ORDER」依次尝试 */
    private suspend fun resolveManual(no: String, creds: Map<Platform, Credential>): ExpressItem {
        val channels = loadPickupChannels()
        val preferred = when (CourierCompany.of(no)) {
            CourierCompany.SF -> Platform.SF
            else -> null
        }
        val candidates = buildList {
            channels[no]?.let { add(it) }
            preferred?.let { add(it) }
            addAll(FALLBACK_ORDER)
        }.distinct().filter { creds.containsKey(it) }
        for (plat in candidates) {
            val code = try {
                adapters.getValue(plat).fetchPickupCode(creds.getValue(plat), no)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Express", "manual $no: ${platformLabel(plat)} fetchPickupCode 失败", e)
                null
            } ?: continue
            if (channels[no] != plat) {
                channels[no] = plat
                savePickupChannels(channels)
            }
            return manualItem(no, code.code, plat)
        }
        return manualItem(no, null, null)
    }

    private fun manualItem(no: String, code: String?, source: Platform?) = ExpressItem(
        id = "manual_$no",
        platform = source ?: Platform.TAOBAO,
        station = MANUAL_STATION,
        trackingNo = no,
        pickupCode = code,
        pickupCodeSource = source,
        status = if (code != null) ExpressStatus.WAIT_PICKUP else ExpressStatus.UNKNOWN,
    )

    private fun loadManual(): Map<String, Long> =
        prefs.getStringSet(KEY_MANUAL, emptySet()).orEmpty()
            .mapNotNull(ManualWaybills::decode)
            .sortedBy { it.addedAt }
            .associate { it.no to it.addedAt }

    private fun saveManual(m: Map<String, Long>) {
        prefs.edit().putStringSet(KEY_MANUAL, m.map { ManualWaybills.encode(ManualWaybill(it.key, it.value)) }.toSet()).apply()
    }

    /**
     * 无码待取件互补查询（app 聚合核心逻辑）：
     * 无码（含 pickupCode 实为运单号）的按「上次命中渠道 → FALLBACK_ORDER」依次用运单号调
     * 其他已登录平台的查件接口（fetchPickupCode，各平台 ExpressItem.id 均为运单号）。
     * 订单尾号码（"出示单号后N位"型，如拼多多尾5位）：不问快递公司（正则识别对申通等并不可靠），
     * 只要拿到的是尾号码就先调淘宝菜鸟升级 1-2-3001 格式真码，失败再回退现有码。
     * 查到 → 填码并记录来源渠道（持久化，下次优先）；查不到 → 原样展示（回退运单号/后五位）。
     */
    private suspend fun backfillPickupCodes(
        items: List<ExpressItem>,
        creds: Map<Platform, Credential>,
    ): List<ExpressItem> {
        val channels = loadPickupChannels()
        var channelsDirty = false
        val out = mutableListOf<ExpressItem>()
        for (item0 in items) {
            val no = item0.trackingNo
            if (no == null) {
                out += item0
                continue
            }
            // 归一化校验：菜鸟格式/短码/描述文本（含出示后N位）视为已有码；
            // 运单号回填（等于单号或长纯数字）视为无码 → 清掉走互补查询
            val norm = PickupCodeNormalizer.normalize(item0.pickupCode, no)
            val hasCode = norm != null
            val item = if (hasCode) item0 else item0.copy(pickupCode = null)
            // 订单尾号码：不识别快递公司，一律先调淘宝菜鸟升级真码（非淘宝来源时）
            val wantCainiao = norm?.hint != null &&
                item0.pickupCodeSource != Platform.TAOBAO &&
                creds.containsKey(Platform.TAOBAO)
            if (wantCainiao) {
                val tbCode = try {
                    adapters.getValue(Platform.TAOBAO).fetchPickupCode(creds.getValue(Platform.TAOBAO), no)
                } catch (e: Exception) {
                    AppLog.e("Express", "fillCode $no: 淘宝 fetchPickupCode 失败", e)
                    null
                }
                if (tbCode != null) {
                    channels[no] = Platform.TAOBAO
                    channelsDirty = true
                    out += item.copy(pickupCode = tbCode.code, pickupCodeSource = Platform.TAOBAO)
                    continue
                }
                // 淘宝没出真码：保留现有码（后五位等）展示
                out += item0
                continue
            }
            if (hasCode) {
                out += item0
                continue
            }
            val recorded = channels[no]
            val candidates = buildList {
                if (recorded != null && recorded != item.platform && creds.containsKey(recorded)) add(recorded)
                for (p in FALLBACK_ORDER) {
                    if (p != item.platform && p != recorded && creds.containsKey(p)) add(p)
                }
            }
            var filled: ExpressItem? = null
            for (plat in candidates) {
                val code = try {
                    adapters.getValue(plat).fetchPickupCode(creds.getValue(plat), no)
                } catch (e: Exception) {
                    AppLog.e("Express", "fillCode $no: ${platformLabel(plat)} fetchPickupCode 失败", e)
                    null // 单渠道失败不阻断，继续下一个
                }
                if (code != null) {
                    filled = item.copy(pickupCode = code.code, pickupCodeSource = plat)
                    if (channels[no] != plat) {
                        channels[no] = plat
                        channelsDirty = true
                    }
                    break
                }
            }
            out += filled ?: item
        }
        if (channelsDirty) savePickupChannels(channels)
        return out
    }

    /** 搜索查到的单加入跟踪（已签收的不加） */
    private fun addTracked(no: String, plat: Platform) {
        if (_tracked.value[no] == plat) return
        val m = _tracked.value + (no to plat)
        _tracked.value = m
        saveTracked(m)
    }

    private fun loadTracked(): Map<String, Platform> =
        prefs.getStringSet(KEY_TRACKED, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap()

    private fun saveTracked(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_TRACKED, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /** 取件码来源渠道（互补查询命中时记录，`no|PLATFORM` StringSet），下次补码优先走该渠道 */
    private fun loadPickupChannels(): MutableMap<String, Platform> =
        prefs.getStringSet(KEY_PICKUP_CHANNELS, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap().toMutableMap()

    private fun savePickupChannels(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_PICKUP_CHANNELS, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /**
     * 运单号查询路由（不再按快递公司正则细分——对申通/中通等纯数字段并不可靠）：
     * - 顺丰（SF 前缀）→ 顺丰自有接口（列表外单号需手机尾号验证）；
     * - 圆通（YT 前缀）→ 淘宝菜鸟 + 拼多多并行取码（菜鸟结果优先）；
     * - 其他一切单号 → 淘宝（菜鸟代收）→ 拼多多（多多驿站）顺序兜底。
     */
    fun lookupByTrackingNo(waybill: String) {
        val no = waybill.trim().uppercase()
        if (no.isEmpty()) return
        _ui.value = _ui.value.copy(searchResult = null, searchNotice = null)
        val creds = sessionRepo.credentials.value
        val company = CourierCompany.of(no) ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(searching = true)
            try {
                // 1) 顺丰单号
                if (company == CourierCompany.SF) {
                    val cred = creds[Platform.SF]
                    if (cred == null) {
                        AppLog.w("Express", "lookup $no: 顺丰未登录，跳过")
                        _ui.value = _ui.value.copy(searching = false, searchNotice = "请先在「我的」页登录顺丰")
                        return@launch
                    }
                    val code = try {
                        adapters.getValue(Platform.SF).fetchPickupCode(cred, no)
                    } catch (e: Exception) {
                        AppLog.e("Express", "lookup $no: 顺丰 fetchPickupCode 失败", e)
                        null
                    }
                    if (code != null) {
                        addTracked(no, Platform.SF)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                    } else {
                        // 不在列表 → 引导输入手机后4位（sf.md §5.2 防遍历验证）
                        _ui.value = _ui.value.copy(
                            searching = false,
                            needSuffixFor = no,
                            searchNotice = "该运单不在列表中，需验证收件手机后 4 位查询",
                        )
                    }
                    return@launch
                }
                // 2) 圆通（YT 前缀，无歧义）：淘宝菜鸟 + 拼多多并行取码（菜鸟优先）
                if (company == CourierCompany.YTO) {
                    coroutineScope {
                        val tbDef = async {
                            creds[Platform.TAOBAO]?.let { c ->
                                try {
                                    adapters.getValue(Platform.TAOBAO).fetchPickupCode(c, no)
                                } catch (e: Exception) {
                                    AppLog.e("Express", "lookup $no: 淘宝 fetchPickupCode 失败", e)
                                    null
                                }
                            }
                        }
                        val pddDef = async {
                            creds[Platform.PDD]?.let { c ->
                                try {
                                    adapters.getValue(Platform.PDD).fetchPickupCode(c, no)
                                } catch (e: Exception) {
                                    AppLog.e("Express", "lookup $no: 拼多多 fetchPickupCode 失败", e)
                                    null
                                }
                            }
                        }
                        val code = tbDef.await() ?: pddDef.await()
                        if (code != null) {
                            addTracked(no, code.platform)
                            _ui.value = _ui.value.copy(searching = false, searchResult = code)
                            return@coroutineScope
                        }
                        // 各渠道都没查到（未登录/单不在驿站在架）
                        val channels = listOfNotNull(
                            "淘宝菜鸟".takeIf { creds.containsKey(Platform.TAOBAO) },
                            "拼多多".takeIf { creds.containsKey(Platform.PDD) },
                        ).ifEmpty { listOf("（淘宝/拼多多均未登录）") }
                        _ui.value = _ui.value.copy(
                            searching = false,
                            searchNotice = "未查到${company.label} $no 的取件码（已查${channels.joinToString("/")}；" +
                                "可能已取出或不在驿站在架列表）",
                        )
                    }
                    return@launch
                }
                // 3) 其他单号 → 淘宝（菜鸟代收）→ 拼多多（多多驿站）兜底
                val tbCred = creds[Platform.TAOBAO]
                if (tbCred != null) {
                    val code = try {
                        adapters.getValue(Platform.TAOBAO).fetchPickupCode(tbCred, no)
                    } catch (e: Exception) {
                        AppLog.e("Express", "lookup $no: 淘宝 fetchPickupCode 失败", e)
                        null
                    }
                    if (code != null) {
                        addTracked(no, Platform.TAOBAO)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                        return@launch
                    }
                }
                val pddCred = creds[Platform.PDD]
                if (pddCred != null) {
                    val code = try {
                        adapters.getValue(Platform.PDD).fetchPickupCode(pddCred, no)
                    } catch (e: Exception) {
                        AppLog.e("Express", "lookup $no: 拼多多 fetchPickupCode 失败", e)
                        null
                    }
                    if (code != null) {
                        addTracked(no, Platform.PDD)
                        _ui.value = _ui.value.copy(searching = false, searchResult = code)
                        return@launch
                    }
                }
                _ui.value = _ui.value.copy(
                    searching = false,
                    searchNotice = "未查到 $no 的取件码（已查淘宝/拼多多，未登录或查不到）",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(searching = false, searchNotice = "查询失败：${e.message}")
            }
        }
    }

    /**
     * 单号 + 手机后4位验证查询：顺丰 v2/getWaybillPickupCode（逐驿站尝试）。
     */
    fun lookupWithSuffix(suffix: String) {
        val no = _ui.value.needSuffixFor ?: return
        val s = suffix.trim()
        if (s.length != 4 || !s.all { it.isDigit() }) {
            _ui.value = _ui.value.copy(searchNotice = "请输入 4 位数字手机尾号")
            return
        }
        val cred = sessionRepo.credentials.value[Platform.SF] ?: run {
            _ui.value = _ui.value.copy(searchNotice = "请先在「我的」页登录顺丰")
            return
        }
        _ui.value = _ui.value.copy(searching = true, searchNotice = null)
        viewModelScope.launch {
            try {
                val code = (adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter)
                    .fetchPickupCodeWithSuffix(cred, no, s)
                if (code != null) addTracked(no, Platform.SF)
                _ui.value = if (code != null) {
                    _ui.value.copy(searching = false, searchResult = code, needSuffixFor = null)
                } else {
                    _ui.value.copy(
                        searching = false,
                        searchNotice = "尾号 $s 未查到取件码（尾号错误/已取走/运单号有误）",
                    )
                }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(searching = false, searchNotice = "查询失败：${e.message}")
            }
        }
    }

    /** 收回尾号验证引导（点关闭/空白处） */
    fun dismissSuffix() {
        _ui.value = _ui.value.copy(needSuffixFor = null, searchNotice = null)
    }

    fun clearSearch() {
        _ui.value = _ui.value.copy(searchResult = null, searchNotice = null, needSuffixFor = null)
    }

    private fun platformLabel(p: Platform) = when (p) {
        Platform.SF -> "顺丰"
        Platform.TAOBAO -> "淘宝"
        Platform.JD -> "京东"
        Platform.PDD -> "拼多多"
    }

    private companion object {
        const val MAX_PAGES = 3
        const val PAGE_SIZE = 10
        const val PREFS_NAME = "express_tracked"
        const val KEY_TRACKED = "tracked_nos"
        const val KEY_PICKUP_CHANNELS = "pickup_channels"
        const val KEY_MANUAL = "manual_waybills"
        const val MAX_MANUAL = 20
        const val MANUAL_STATION = "手动添加"

        /** 无码待取件互补查询顺序：淘宝优先（菜鸟系主渠道，multiOneSearch 轻量）、PDD 较重（54001 限频）靠后 */
        val FALLBACK_ORDER = listOf(Platform.TAOBAO, Platform.PDD, Platform.SF)
    }
}
