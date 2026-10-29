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

    /** null = 全部区；否则为 sourceId。 */
    var filter: String? = null

    /** false=最近（浏览记录） true=收藏 */
    var sectionFavorites: Boolean = true

    fun applyFilter(f: String?) {
        filter = f
        load()
    }

    fun setSection(favorites: Boolean) {
        if (sectionFavorites == favorites) return
        sectionFavorites = favorites
        load()
    }

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            try {
                _threads.value = if (sectionFavorites) repo.favorites(filter) else repo.recents(filter)
            } catch (_: Exception) {}
            finally { _loading.value = false }
        }
    }

    /** 清空最近浏览（仅最近段入口）。 */
    fun clearRecents() {
        viewModelScope.launch {
            try { repo.clearRecents() } catch (_: Exception) {}
            load()
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
