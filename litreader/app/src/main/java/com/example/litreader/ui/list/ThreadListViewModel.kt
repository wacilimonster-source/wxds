package com.example.litreader.ui.list

import android.content.SharedPreferences
import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.source.SourceRegistry
import com.example.litreader.data.source.SourceStyle
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

class ThreadListViewModel(private val repo: BookRepository, private val sourceId: String) : ViewModel() {
    private val source = SourceRegistry.get(sourceId) ?: SourceRegistry.first()
    private val isImage = source.style == SourceStyle.IMAGE

    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _tags = MutableLiveData<List<TagCount>>()
    val tags: LiveData<List<TagCount>> = _tags

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /** 贴图区跳到已归档的深页时回退到的页码（Fragment 弹提示用），消费后清零。 */
    private val _archivedNotice = MutableLiveData(0)
    val archivedNotice: LiveData<Int> = _archivedNotice

    fun clearArchivedNotice() { _archivedNotice.value = 0 }

    var page = 1
    var category = ""
    var tag = ""
    /** -1=全部 0=未读 1=已读 2=已读完（仅文学区使用） */
    var readFilter = -1
    var reloading = false

    private var totalCount = 0
    private var siteTotalPages = 0
    private var validPages = 0

    /** 文学区 30 条/页（本地目录）；贴图区 100 条/页（与站点页对齐，有效页深以探测值为准）。 */
    val totalPages: Int
        get() = if (isImage) {
            val cap = if (validPages in 1 until siteTotalPages) validPages else siteTotalPages
            max(cap, page)
        }
        else if (totalCount == 0) 1
        else ceil(totalCount.toDouble() / BookRepository.PAGE_SIZE).toInt()

    fun load(page: Int, category: String = this.category, force: Boolean = false) {
        val prevPage = this.page
        this.page = page
        this.category = category
        _loading.value = true
        viewModelScope.launch {
            try {
                var list = if (isImage) {
                    repo.galleryPage(sourceId, page, force)
                } else {
                    repo.page(sourceId, page, BookRepository.PAGE_SIZE, category, tag, readFilter)
                }
                if (isImage && list.isEmpty() && page > 1) {
                    // 站点页脚总页数虚高（早期帖子已归档，深页返回空壳）：
                    // 先强制重抓一次防瞬时抖动，仍空则二分探测最大有效页并回退
                    list = repo.galleryPage(sourceId, page, forceRefresh = true)
                    if (list.isEmpty()) {
                        val valid = repo.probeValidPages(sourceId, minOf(prevPage, page - 1), page)
                        repo.saveValidPages(sourceId, valid)
                        this@ThreadListViewModel.page = valid
                        list = repo.galleryPage(sourceId, valid)
                        _archivedNotice.value = valid
                    }
                }
                _threads.value = list
                if (isImage) {
                    siteTotalPages = repo.sitePageCount(sourceId)
                    validPages = repo.validPageCount(sourceId)
                    _tags.value = emptyList()
                } else {
                    totalCount = repo.categoryCount(sourceId, category, tag, readFilter)
                    _tags.value = repo.tagCounts(sourceId)
                }
                _error.value = if (list.isEmpty() && page == 1 && !reloading && !force) "暂无数据" else null
            } catch (e: Exception) {
                _error.value = "加载失败：${e.message ?: "网络错误"}"
            } finally {
                _loading.value = false
                reloading = false
            }
        }
    }

    fun selectTag(t: String) {
        tag = t
        load(1)
    }

    fun selectReadFilter(f: Int) {
        readFilter = f
        load(1)
    }

    fun refresh() {
        reloading = true
        if (isImage) load(page, force = true) else load(1)
    }

    fun nextPage() = load(page + 1)

    fun prevPage() = if (page > 1) load(page - 1) else load(1)

    fun toggleFavorite(t: ThreadEntity) {
        viewModelScope.launch {
            repo.setFavorite(t.tid, !t.favorite)
            val cur = _threads.value ?: return@launch
            _threads.value = cur.map { if (it.tid == t.tid) it.copy(favorite = !t.favorite) else it }
        }
    }
}

class ThreadListVmFactory(
    private val db: AppDatabase,
    private val prefs: SharedPreferences,
    private val sourceId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T =
        ThreadListViewModel(BookRepository(db, prefs), sourceId) as T
}
