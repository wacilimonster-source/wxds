package com.example.litreader.ui.list

import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import kotlinx.coroutines.launch

class ThreadListViewModel(private val repo: BookRepository) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    var page = 1
    var category = ""
    var onlyFavorite = false
    var reloading = false

    fun load(page: Int, category: String = this.category) {
        this.page = page
        this.category = category
        _loading.value = true
        viewModelScope.launch {
            try {
                val list = if (onlyFavorite) repo.favorites() else repo.page(page, BookRepository.PAGE_SIZE, category, false)
                _threads.value = list
                _error.value = if (list.isEmpty() && !onlyFavorite && page == 1 && !reloading) "暂无数据，下拉刷新试试" else null
            } catch (e: Exception) {
                _error.value = "加载失败：${e.message ?: "网络错误"}"
            } finally {
                _loading.value = false
                reloading = false
            }
        }
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
            _threads.value = if (onlyFavorite) cur.filter { it.tid != t.tid }
            else cur.map { if (it.tid == t.tid) it.copy(favorite = !t.favorite) else it }
        }
    }

    fun search(q: String) {
        _loading.value = true
        viewModelScope.launch {
            try {
                _threads.value = repo.search(q)
            } catch (e: Exception) {
                _error.value = "搜索失败：${e.message ?: "本地错误"}"
            } finally { _loading.value = false }
        }
    }
}

class ThreadListVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = ThreadListViewModel(BookRepository(db)) as T
}
