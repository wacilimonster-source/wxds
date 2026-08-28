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

    fun search(q: String) {
        query = q
        _loading.value = true
        viewModelScope.launch {
            try { _threads.value = repo.search(q) }
            catch (e: Exception) { _threads.value = emptyList() }
            finally { _loading.value = false }
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
