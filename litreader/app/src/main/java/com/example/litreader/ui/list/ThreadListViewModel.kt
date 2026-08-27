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

    var page = 1
    var category = ""

    fun load(page: Int, category: String = this.category) {
        this.category = category
        _loading.value = true
        viewModelScope.launch {
            try {
                _threads.value = repo.loadList(page, category)
            } finally { _loading.value = false }
        }
    }

    fun search(q: String) {
        _loading.value = true
        viewModelScope.launch {
            _threads.value = if (q.isBlank()) repo.cached() else repo.search(q)
            _loading.value = false
        }
    }

    fun crawlAll() {
        _loading.value = true
        viewModelScope.launch {
            repo.crawlAll(category)
            _threads.value = repo.cached()
            _loading.value = false
        }
    }
}

class ThreadListVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = ThreadListViewModel(BookRepository(db)) as T
}
