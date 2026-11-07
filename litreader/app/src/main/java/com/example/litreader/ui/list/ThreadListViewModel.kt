package com.example.litreader.ui.list

import android.content.SharedPreferences
import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.repo.MarkOps
import com.example.litreader.data.repo.MarkSnapshot
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import com.example.litreader.util.KeywordBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

/**
 * 文学/贴图列表 VM：accumulated 存原始行，发布前统一过滤（不喜欢+关键词）。
 * 时序保证：load 用 requestId、loadMore 用单飞 Job，最后一次意图生效，迟到响应丢弃。
 */
class ThreadListViewModel(
    private val repo: BookRepository,
    private val prefs: SharedPreferences,
    private val sourceId: String
) : ViewModel() {
    private val source = SourceRegistry.get(sourceId) ?: SourceRegistry.first()
    private val isImage = source.style == SourceStyle.IMAGE

    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _tags = MutableLiveData<List<TagCount>>()
    val tags: LiveData<List<TagCount>> = _tags

    /** 首屏骨架：仅 accumulated 为空时的首次加载为 true；有缓存先显示不进骨架。 */
    private val _firstLoading = MutableLiveData(false)
    val firstLoading: LiveData<Boolean> = _firstLoading

    /** 刷新中（顶部指示，原列表保留）。 */
    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** 顶部错误轻提示（保留缓存场景），Fragment 消费后清空。 */
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /** 空态分型：无目录 / 筛选零结果 / 网络失败；null=非空态。 */
    enum class EmptyReason { NO_CATALOG, FILTERED, NETWORK }
    private val _emptyReason = MutableLiveData<EmptyReason?>(null)
    val emptyReason: LiveData<EmptyReason?> = _emptyReason

    /** 追加失败：列表尾部重试入口；失败时 page/items 保持一致。 */
    private val _appendError = MutableLiveData(false)
    val appendError: LiveData<Boolean> = _appendError

    /** 追加进行中（尾部「加载中」）。 */
    private val _appending = MutableLiveData(false)
    val appending: LiveData<Boolean> = _appending

    /** 已到底（尾部「已经到底了」，不再发请求）。 */
    var noMore = false
        private set

    /** 总页数是否已知（贴图站点页数未取得前禁用跳页提交）。 */
    val totalKnown: Boolean
        get() = !isImage || siteTotalPages > 0 || validPages > 0

    /** 标记写入与撤销（快照+版本，见 MarkOps 契约）。 */
    val markOps = MarkOps(repo)

    private val _opFailure = MutableLiveData(false)
    val opFailure: LiveData<Boolean> = _opFailure
    fun consumeOpFailure() { _opFailure.value = false }

    /** 无限滚动累积的已加载各页原始行；跳页/刷新时重置。 */
    private val accumulated = mutableListOf<ThreadEntity>()

    // ---- 请求时序 ----
    private var loadJob: Job? = null
    private var appendJob: Job? = null
    private var requestId = 0
    private var appendInFlight = false

    /** 最后成功加载的页码（页码栏显示值）。 */
    var page = 1
        private set
    var category = ""
    var tag = ""

    /** 已应用的阅读状态筛选（面板草稿与应用分离，见 FilterSheet）。 */
    var readFilter = -1

    /** 已加载页范围（文本=本地页，贴图=站点页），reloadLoadedPages 只重载该范围。 */
    private var minLoadedPage = 1
    private var maxLoadedPage = 1

    private var totalCount = 0
    private var siteTotalPages = 0
    private var validPages = 0
    /** 标签聚合只在首次/目录同步后重查；切筛选或翻页时复用，省一次 GROUP BY。 */
    private var tagsLoaded = false

    /** 文学区 30 条/页（本地目录）；贴图区 100 条/页（与站点页对齐，有效页深以探测值为准）。 */
    val totalPages: Int
        get() = if (isImage) {
            val cap = if (validPages in 1 until siteTotalPages) validPages else siteTotalPages
            max(cap, page)
        }
        else if (totalCount == 0) 1
        else ceil(totalCount.toDouble() / BookRepository.PAGE_SIZE).toInt()

    /** 跳页/刷新：取消在途请求，重置累积，只载入目标页。 */
    fun load(page: Int, category: String = this.category, force: Boolean = false) {
        loadJob?.cancel()
        appendJob?.cancel()
        appendInFlight = false
        _appendError.value = false
        val reqId = ++requestId
        val target = page.coerceAtLeast(1)
        val prevPage = this.page
        this.page = target
        this.category = category
        val first = accumulated.isEmpty()
        _loading.value = true
        if (first) _firstLoading.value = true
        loadJob = viewModelScope.launch {
            try {
                var list = fetch(target, force)
                if (isImage && list.isEmpty() && target > 1) {
                    // 站点页脚总页数虚高（早期帖子已归档，深页返回空壳）：
                    // 先强制重抓一次防瞬时抖动，仍空则二分探测最大有效页并回退
                    list = repo.galleryPage(sourceId, target, forceRefresh = true)
                    if (list.isEmpty()) {
                        val valid = repo.probeValidPages(sourceId, minOf(prevPage, target - 1), target)
                        repo.saveValidPages(sourceId, valid)
                        list = repo.galleryPage(sourceId, valid)
                        _archivedNotice.value = valid
                        if (reqId == requestId) this@ThreadListViewModel.page = valid
                    }
                }
                if (reqId != requestId) return@launch  // 迟到响应：已被更新意图取代
                accumulated.clear()
                accumulated += list
                minLoadedPage = this@ThreadListViewModel.page
                maxLoadedPage = this@ThreadListViewModel.page
                noMore = list.isEmpty()
                _threads.value = filtered()
                _emptyReason.value = emptyReasonFor(list)
                if (isImage) {
                    siteTotalPages = repo.sitePageCount(sourceId)
                    validPages = repo.validPageCount(sourceId)
                    _tags.value = emptyList()
                } else {
                    totalCount = repo.categoryCount(sourceId, category, tag, readFilter)
                    if (!tagsLoaded) {
                        _tags.value = repo.tagCounts(sourceId)
                        tagsLoaded = true
                    }
                }
            } catch (e: CancellationException) {
                throw e  // 取消向上传递，不当作失败
            } catch (e: Exception) {
                if (reqId != requestId) return@launch
                this@ThreadListViewModel.page = prevPage  // 失败回滚页码，items 不动
                if (accumulated.isEmpty()) _emptyReason.value = EmptyReason.NETWORK
                else _error.value = "加载失败：${e.message ?: "网络错误"}"
            } finally {
                if (reqId == requestId) {
                    _loading.value = false
                    _firstLoading.value = false
                }
            }
        }
    }

    /** 无限滚动追加：滚到底自动触发；失败进尾部重试，不清列表、不增页码。 */
    fun loadMore() {
        if (appendInFlight || appendJob?.isActive == true || noMore ||
            _loading.value == true || page >= totalPages) return
        appendInFlight = true
        _appending.value = true
        _appendError.value = false
        appendJob = viewModelScope.launch {
            try {
                val rows = fetch(page + 1)
                if (rows.isEmpty()) {
                    noMore = true   // 到底（含站点归档边界），不再触发
                    _threads.value = filtered()
                } else {
                    accumulated += rows
                    page += 1
                    maxLoadedPage = page
                    _threads.value = filtered()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _appendError.value = true
            } finally {
                appendInFlight = false
                _appending.value = false
            }
        }
    }

    /** 尾部重试：只追加一次，失败重新置位（不自动死循环）。 */
    fun retryAppend() {
        if (!_appendError.value!!) return
        loadMore()
    }

    /** 贴图/文学共用的取页入口。 */
    private suspend fun fetch(target: Int, force: Boolean = false): List<ThreadEntity> =
        if (isImage) repo.galleryPage(sourceId, target, force)
        else repo.page(sourceId, target, BookRepository.PAGE_SIZE, category, tag, readFilter)

    /**
     * 从阅读器/论坛详情返回：只回读变化的那一条并合并进当前列表。
     * 不喜欢的行在 matchesFilter 中被移除；不在列表且处于已读筛选时回退整页重查。
     */
    fun refreshThread(tid: String) {
        if (tid.isEmpty()) return
        viewModelScope.launch {
            val fresh = runCatching { repo.byId(tid) }.getOrNull() ?: return@launch
            val idx = accumulated.indexOfFirst { it.tid == tid }
            if (idx < 0) {
                if (readFilter != -1) reloadLoadedPages()
                return@launch
            }
            // 当前正按已读状态筛选时，刚读过的帖子要从「未读」等列表里移除；不喜欢的一样隐藏
            val matchesFilter = (readFilter == -1 || fresh.readState == readFilter) && !fresh.dislike
            if (matchesFilter && accumulated[idx] == fresh) return@launch
            if (matchesFilter) accumulated[idx] = fresh else accumulated.removeAt(idx)
            _threads.value = filtered()
        }
    }

    /** 页签返回/目录同步完成后刷新已加载范围（按实际 min..max，深页不再回拉 1…N）。 */
    fun reloadLoadedPages() {
        val range = minLoadedPage..maxLoadedPage
        if (range.isEmpty()) return
        _loading.value = true
        viewModelScope.launch {
            try {
                val all = if (isImage) {
                    range.flatMap { repo.galleryPage(sourceId, it) }
                } else {
                    repo.page(sourceId, range.first, (range.last - range.first + 1) * BookRepository.PAGE_SIZE, category, tag, readFilter)
                }
                accumulated.clear()
                accumulated += all
                noMore = false
                _threads.value = filtered()
                _emptyReason.value = emptyReasonFor(all)
                if (!isImage) {
                    _tags.value = repo.tagCounts(sourceId)
                    tagsLoaded = true
                }
            } catch (_: Exception) {
                // 刷新失败保留现有列表
            } finally {
                _loading.value = false
            }
        }
    }

    /** 屏蔽词命中数（更多菜单标题「关键词屏蔽（N）」用；不喜欢的条目不计入）。 */
    fun blockedCount(): Int {
        val kws = KeywordBlock.list(prefs, sourceId)
        if (kws.isEmpty()) return 0
        return accumulated.count { !it.dislike && KeywordBlock.hit(it.title, kws) }
    }

    /** 关键词屏蔽 + 不喜欢过滤：accumulated 存原始数据，发布时过滤（增删词/标记后 refilter 即时生效）。 */
    private fun filtered(): List<ThreadEntity> {
        val kws = KeywordBlock.list(prefs, sourceId)
        return accumulated.filter { !it.dislike && (kws.isEmpty() || !KeywordBlock.hit(it.title, kws)) }
    }

    /** 屏蔽词变化后重发布（不重查库、不动滚动位置，DiffUtil 平滑增删）。 */
    fun refilter() {
        if (accumulated.isEmpty()) return
        _threads.value = filtered()
    }

    fun selectTag(t: String) {
        tag = t
        load(1)
    }

    /** 应用阅读状态筛选（FilterSheet 显式提交后才走到这里）。 */
    fun applyReadFilter(f: Int) {
        if (readFilter == f) return
        readFilter = f
        load(1)
    }

    /** 清除筛选：重置标签与阅读状态并回第一页（空态「清除筛选」入口）。 */
    fun clearFilters() {
        tag = ""
        readFilter = -1
        load(1)
    }

    fun refresh() {
        load(1, force = isImage)
    }

    // ---- 条目操作（更多菜单/收藏按钮/左滑）：绝对 setter + 快照撤销 ----

    /** 收藏绝对 setter；撤销恢复原值，失败复原 UI 并提示。 */
    fun setFavorite(t: ThreadEntity, target: Boolean, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setFavorite(t.tid, target) }
            if (r == null) {
                _opFailure.value = true
                mergeLocal(t.tid)
            } else {
                mergeLocal(t.tid)
                onUndoReady(r.first, r.second)
            }
        }
    }

    /** 不喜欢绝对 setter（repo 内自动取消收藏）；撤销恢复原收藏态。 */
    fun setDislike(t: ThreadEntity, target: Boolean, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setDislike(t.tid, target) }
            if (r == null) {
                _opFailure.value = true
                mergeLocal(t.tid)
            } else {
                mergeLocal(t.tid)
                onUndoReady(r.first, r.second)
            }
        }
    }

    /** 已读完绝对 setter。 */
    fun setFinished(tid: String, target: Boolean) {
        viewModelScope.launch {
            val r = markOps.apply(tid) { repo.setFinished(tid, target) }
            if (r == null) _opFailure.value = true
            mergeLocal(tid)
        }
    }

    /** 撤销：版本不匹配（已被后续操作取代）时静默忽略；失败提示。 */
    fun undoMark(tid: String, snap: MarkSnapshot, version: Int) {
        viewModelScope.launch {
            when (markOps.undo(tid, snap, version)) {
                MarkOps.UndoResult.OK -> mergeLocal(tid)
                MarkOps.UndoResult.STALE -> {}
                MarkOps.UndoResult.FAILED -> {
                    _opFailure.value = true
                    mergeLocal(tid)
                }
            }
        }
    }

    /** 单条回读并就地合并进 accumulated（行已不在当前列表则只发布），DiffUtil 平滑增删。 */
    private fun mergeLocal(tid: String) {
        viewModelScope.launch {
            val fresh = runCatching { repo.byId(tid) }.getOrNull() ?: return@launch
            val idx = accumulated.indexOfFirst { it.tid == tid }
            if (idx >= 0) accumulated[idx] = fresh
            _threads.value = filtered()
        }
    }

    private fun emptyReasonFor(rows: List<ThreadEntity>): EmptyReason? {
        if (rows.isNotEmpty()) return null
        return when {
            readFilter != -1 || tag.isNotEmpty() -> EmptyReason.FILTERED
            isImage -> EmptyReason.NETWORK  // 贴图：无缓存条目
            else -> EmptyReason.NO_CATALOG  // 文学：目录未同步
        }
    }

    /** 贴图深页归档回退提示（Fragment 弹出后消费清零）。 */
    private val _archivedNotice = MutableLiveData(0)
    val archivedNotice: LiveData<Int> = _archivedNotice
    fun clearArchivedNotice() { _archivedNotice.value = 0 }
}

class ThreadListVmFactory(
    private val db: AppDatabase,
    private val prefs: SharedPreferences,
    private val sourceId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T =
        ThreadListViewModel(BookRepository(db, prefs), prefs, sourceId) as T
}
