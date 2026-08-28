package com.example.litreader.ui.fav

import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import kotlinx.coroutines.launch

class FavoritesViewModel(private val repo: BookRepository) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            try { _threads.value = repo.favorites() }
            catch (_: Exception) {}
            finally { _loading.value = false }
        }
    }

    fun toggleFavorite(t: ThreadEntity) {
        viewModelScope.launch {
            repo.setFavorite(t.tid, !t.favorite)
            load()
        }
    }
}

class FavoritesVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = FavoritesViewModel(BookRepository(db)) as T
}
