package com.example.litreader.ui.list

import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.TagCount
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import kotlinx.coroutines.launch
import kotlin.math.ceil

class ThreadListViewModel(private val repo: BookRepository, private val sourceId: String) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _tags = MutableLiveData<List<TagCount>>()
    val tags: LiveData<List<TagCount>> = _tags

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    var page = 1
    var category = ""
    var tag = ""
    var reloading = false

    var totalCount = 0
        private set
    val totalPages: Int
        get() = if (totalCount == 0) 1 else ceil(totalCount.toDouble() / BookRepository.PAGE_SIZE).toInt()

    fun load(page: Int, category: String = this.category) {
        this.page = page
        this.category = category
        _loading.value = true
        viewModelScope.launch {
            try {
                val list = repo.page(sourceId, page, BookRepository.PAGE_SIZE, category, tag)
                _threads.value = list
                totalCount = repo.categoryCount(sourceId, category, tag)
                _tags.value = repo.tagCounts(sourceId)
                _error.value = if (list.isEmpty() && page == 1 && !reloading) "暂无数据" else null
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

    fun refresh() {
        reloading = true
        load(1)
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

class ThreadListVmFactory(private val db: AppDatabase, private val sourceId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T =
        ThreadListViewModel(BookRepository(db), sourceId) as T
}
