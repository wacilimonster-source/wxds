package com.example.litreader.ui.list

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.repo.BookRepository
import kotlinx.coroutines.launch

/**
 * 文学区目录同步：MainActivity 进 App 时触发一次；
 * 列表页下拉/刷新按钮再次触发。同跑中去重。
 */
class CatalogSyncViewModel(
    private val repo: BookRepository,
    private val sourceId: String
) : ViewModel() {

    data class SyncState(
        val running: Boolean,
        val page: Int,
        val totalPages: Int,
        val totalNew: Int,
        val error: String?
    )

    private val _state = MutableLiveData(SyncState(false, 0, 0, 0, null))
    val state: LiveData<SyncState> = _state

    private var running = false

    fun sync() {
        if (running) return
        running = true
        _state.value = SyncState(true, 0, 0, 0, null)
        viewModelScope.launch {
            try {
                repo.syncCatalog(sourceId) { p ->
                    _state.value = SyncState(true, p.page, p.totalPages, p.totalNew, null)
                }
                _state.value = SyncState(false, 0, 0, 0, null)
            } catch (e: Exception) {
                _state.value = SyncState(false, 0, 0, 0, e.message ?: "网络错误")
            } finally {
                running = false
            }
        }
    }
}

class CatalogSyncVmFactory(
    private val db: AppDatabase,
    private val prefs: SharedPreferences,
    private val sourceId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        CatalogSyncViewModel(BookRepository(db, prefs), sourceId) as T
}
