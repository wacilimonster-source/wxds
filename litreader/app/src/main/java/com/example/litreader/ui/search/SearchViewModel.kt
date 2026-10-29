package com.example.litreader.ui.search

import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import kotlinx.coroutines.launch

class SearchViewModel(private val repo: BookRepository) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    var query = ""
        private set

    /** null=全部区；否则 sourceId。 */
    var filter: String? = null

    /** 0=最新 1=最多点赞 2=最多回复 */
    var sortMode = 0

    fun search(q: String) {
        query = q
        reload()
    }

    fun applyFilter(f: String?) {
        if (filter == f) return
        filter = f
        if (query.isNotEmpty()) reload()
    }

    /** 切排序只对当前结果内存重排，不重查库。 */
    fun applySort(mode: Int) {
        if (sortMode == mode) return
        sortMode = mode
        val cur = _threads.value ?: return
        _threads.value = sortResults(cur)
    }

    private fun reload() {
        _loading.value = true
        viewModelScope.launch {
            try { _threads.value = sortResults(repo.search(query, filter)) }
            catch (_: Exception) { _threads.value = emptyList() }
            finally { _loading.value = false }
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
            repo.setFavorite(t.tid, !t.favorite)
            val cur = _threads.value ?: return@launch
            _threads.value = cur.map { if (it.tid == t.tid) it.copy(favorite = !t.favorite) else it }
        }
    }
}

class SearchVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = SearchViewModel(BookRepository(db)) as T
}
