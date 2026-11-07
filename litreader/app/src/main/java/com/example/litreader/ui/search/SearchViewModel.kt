package com.example.litreader.ui.search

import android.content.SharedPreferences
import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.repo.MarkOps
import com.example.litreader.data.repo.MarkSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 本地标题搜索：请求可取消（requestId 失效旧响应）、loading/empty/error 状态分离，
 * 异常不得转换为零结果；历史最多 5 条去重置顶。
 */
class SearchViewModel(
    private val repo: BookRepository,
    private val prefs: SharedPreferences
) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** 搜索失败（区别于零结果）：保留已提交词，可重试。 */
    private val _error = MutableLiveData(false)
    val error: LiveData<Boolean> = _error

    /** 标记写入与撤销（与列表/书架同一契约）。 */
    val markOps = MarkOps(repo)

    private val _opFailure = MutableLiveData(false)
    val opFailure: LiveData<Boolean> = _opFailure
    fun consumeOpFailure() { _opFailure.value = false }

    var query = ""
        private set

    /** null=全部区；否则 sourceId。 */
    var filter: String? = null

    /** 0=最新 1=最多点赞 2=最多回复 */
    var sortMode = 0

    private var searchJob: Job? = null
    private var requestId = 0

    fun search(q: String) {
        query = q
        persist()
        if (q.isNotBlank()) rememberHistory(q)
        reload()
    }

    /** 清空：输入、已提交词、结果、last_query 同步归零；历史与来源/排序偏好保留。 */
    fun clearQuery() {
        query = ""
        persist()
        searchJob?.cancel()
        requestId++
        _threads.value = emptyList()
        _error.value = false
        _loading.value = false
    }

    /** 搜索历史（最多 5 条，去重置顶），\u0001 分隔存 prefs。 */
    fun history(): List<String> =
        prefs.getString("history", "")?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList()

    fun removeHistory(q: String) {
        prefs.edit().putString("history", (history() - q).joinToString("\u0001")).apply()
    }

    private fun rememberHistory(q: String) {
        val next = (listOf(q) + history().filter { it != q }).take(5)
        prefs.edit().putString("history", next.joinToString("\u0001")).apply()
    }

    fun applyFilter(f: String?) {
        if (filter == f) return
        filter = f
        persist()
        if (query.isNotEmpty()) reload() else clearQuery()
    }

    /** 切排序只对当前结果内存重排，不重查库。 */
    fun applySort(mode: Int) {
        if (sortMode == mode) return
        sortMode = mode
        persist()
        val cur = _threads.value ?: return
        _threads.value = sortResults(cur)
    }

    /** 失败重试：保留已提交词重新查询。 */
    fun retry() {
        if (query.isNotEmpty()) reload()
    }

    /** 上次搜索的关键词（App 重启/页面重建后恢复用）。 */
    fun lastQuery(): String = prefs.getString("last_query", "") ?: ""

    /** 每次搜索/切区/切排序落盘，保证切换页签或重启后记录不丢。 */
    private fun persist() {
        prefs.edit()
            .putString("last_query", query)
            .putString("zone", filter ?: "")
            .putInt("sort", sortMode)
            .apply()
    }

    private fun reload() {
        searchJob?.cancel()
        val reqId = ++requestId
        val q = query
        val zone = filter
        _loading.value = true
        _error.value = false
        searchJob = viewModelScope.launch {
            try {
                val rows = repo.search(q, zone)   // 以提交时刻的快照执行
                if (reqId != requestId) return@launch  // 迟到响应丢弃
                _threads.value = sortResults(rows)
            } catch (e: CancellationException) {
                throw e  // 取消不当作失败
            } catch (e: Exception) {
                if (reqId != requestId) return@launch
                _threads.value = emptyList()
                _error.value = true
            } finally {
                if (reqId == requestId) _loading.value = false
            }
        }
    }

    private fun sortResults(list: List<ThreadEntity>): List<ThreadEntity> = when (sortMode) {
        1 -> list.sortedByDescending { likeValue(it.likes) }
        2 -> list.sortedByDescending { it.replies.trim().toIntOrNull() ?: 0 }
        else -> list.sortedByDescending { it.timestamp }
    }

    /** 右列赞数：'157'→157、'3.4K'→3400；'2 小時' 这类时间文本和空串视作 0。 */
    private fun likeValue(raw: String): Int {
        val m = Regex("""^([\d.]+)\s*([KkMm]?)$""").find(raw.trim()) ?: return 0
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0
        return when (m.groupValues[2].uppercase()) {
            "K" -> (n * 1000).toInt()
            "M" -> (n * 1_000_000).toInt()
            else -> n.toInt()
        }
    }

    fun toggleFavorite(t: ThreadEntity) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setFavorite(t.tid, !t.favorite) }
            if (r == null) {
                _opFailure.value = true
                return@launch
            }
            val cur = _threads.value ?: return@launch
            _threads.value = cur.map { if (it.tid == t.tid) it.copy(favorite = !t.favorite) else it }
        }
    }

    /** 不喜欢（搜索结果不隐藏条目，仅写状态；撤销按快照）。 */
    fun setDislike(t: ThreadEntity, target: Boolean, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setDislike(t.tid, target) }
            if (r == null) {
                _opFailure.value = true
                return@launch
            }
            val cur = _threads.value ?: return@launch
            _threads.value = cur.map { if (it.tid == t.tid) it.copy(dislike = target, dislikeAt = if (target) System.currentTimeMillis() else 0) else it }
            onUndoReady(r.first, r.second)
        }
    }

    /** 撤销：按快照恢复；版本不匹配（被后续操作取代）时静默忽略。 */
    fun undoMark(tid: String, snap: MarkSnapshot, version: Int) {
        viewModelScope.launch {
            when (markOps.undo(tid, snap, version)) {
                MarkOps.UndoResult.OK -> {
                    val cur = _threads.value ?: return@launch
                    _threads.value = cur.map {
                        if (it.tid == tid) it.copy(favorite = snap.favorite, dislike = snap.dislike, dislikeAt = snap.dislikeAt) else it
                    }
                }
                MarkOps.UndoResult.STALE -> {}
                MarkOps.UndoResult.FAILED -> _opFailure.value = true
            }
        }
    }
}

class SearchVmFactory(
    private val db: AppDatabase,
    private val prefs: SharedPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = SearchViewModel(BookRepository(db), prefs) as T
}
