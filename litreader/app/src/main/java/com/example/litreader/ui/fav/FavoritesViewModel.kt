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

    /** 「继续阅读」置顶卡：最近段 + 全部区时的最新一条浏览记录。 */
    private val _recent = MutableLiveData<ThreadEntity?>()
    val recent: LiveData<ThreadEntity?> = _recent

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
                val list = if (sectionFavorites) repo.favorites(filter) else repo.recents(filter)
                _threads.value = list
                _recent.value =
                    if (!sectionFavorites && filter == null) list.firstOrNull() else null
            } catch (_: Exception) {}
            finally { _loading.value = false }
        }
    }

    /** 清空最近浏览；快照经回调交 Snackbar 撤销。 */
    fun clearRecents(onCleared: (List<Pair<String, Long>>) -> Unit) {
        viewModelScope.launch {
            val snap = try { repo.recentsSnapshot() } catch (_: Exception) { emptyList() }
            try { repo.clearRecents() } catch (_: Exception) {}
            onCleared(snap)
            load()
        }
    }

    /** 撤销清空：按快照恢复浏览时间。 */
    fun restoreRecents(snap: List<Pair<String, Long>>) {
        viewModelScope.launch {
            try { repo.restoreRecents(snap) } catch (_: Exception) {}
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
