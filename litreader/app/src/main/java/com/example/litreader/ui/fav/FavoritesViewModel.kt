package com.example.litreader.ui.fav

import androidx.lifecycle.*
import com.example.litreader.data.db.AppDatabase
import com.example.litreader.data.db.ThreadEntity
import com.example.litreader.data.repo.BookRepository
import com.example.litreader.data.repo.MarkOps
import com.example.litreader.data.repo.MarkSnapshot
import kotlinx.coroutines.launch

class FavoritesViewModel(private val repo: BookRepository) : ViewModel() {
    private val _threads = MutableLiveData<List<ThreadEntity>>()
    val threads: LiveData<List<ThreadEntity>> = _threads

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    /** 继续阅读卡：从最近可读记录派生（按来源过滤）；收藏段也展示，不喜欢段隐藏。 */
    private val _continueCandidate = MutableLiveData<ThreadEntity?>()
    val continueCandidate: LiveData<ThreadEntity?> = _continueCandidate

    /** null = 全部区；否则为 sourceId。 */
    var filter: String? = null

    /** 0=最近（浏览记录） 1=收藏 2=不喜欢 */
    var section: Int = 1

    /** 标记写入与撤销（与列表/搜索同一快照契约）。 */
    val markOps = MarkOps(repo)

    private val _opFailure = MutableLiveData(false)
    val opFailure: LiveData<Boolean> = _opFailure
    fun consumeOpFailure() { _opFailure.value = false }

    fun applyFilter(f: String?) {
        filter = f
        load()
    }

    fun selectSection(s: Int) {
        if (section == s) return
        section = s
        load()
    }

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            try {
                val list = when (section) {
                    1 -> repo.favorites(filter)
                    2 -> repo.disliked(filter)
                    else -> repo.recents(filter)
                }
                _threads.value = list
                // 续读卡候选：最近打开且未 dislike 的记录（最近定义含收藏帖）
                _continueCandidate.value =
                    if (section == 2) null else repo.recents(filter, 1).firstOrNull()
            } catch (_: Exception) {}
            finally { _loading.value = false }
        }
    }

    /** 清空最近浏览；快照经回调交 Snackbar 撤销。失败经 onResult(false) 提示重试。 */
    fun clearRecents(onCleared: (List<Pair<String, Long>>) -> Unit, onFailed: () -> Unit) {
        viewModelScope.launch {
            val snap = try { repo.recentsSnapshot() } catch (_: Exception) { emptyList() }
            val ok = try { repo.clearRecents(); true } catch (_: Exception) { false }
            if (ok) onCleared(snap) else onFailed()
            load()
        }
    }

    /** 撤销清空：按快照恢复浏览时间（清空后重开的记录保留较新 lastOpenAt）。 */
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

    /** 收藏绝对 setter（快照撤销版）：更多菜单/收藏按钮用。 */
    fun setFavoriteSnap(t: ThreadEntity, target: Boolean, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setFavorite(t.tid, target) }
            if (r == null) { _opFailure.value = true; return@launch }
            load()
            onUndoReady(r.first, r.second)
        }
    }

    /** 不喜欢绝对 setter（快照撤销版）：更多菜单用。 */
    fun setDislikeSnap(t: ThreadEntity, target: Boolean, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setDislike(t.tid, target) }
            if (r == null) { _opFailure.value = true; return@launch }
            load()
            onUndoReady(r.first, r.second)
        }
    }

    /** 标记不喜欢（最近/收藏段左滑或更多菜单）：repo 事务内自动取消收藏。 */
    fun dislike(t: ThreadEntity, onUndoReady: (MarkSnapshot, Int) -> Unit) {
        viewModelScope.launch {
            val r = markOps.apply(t.tid) { repo.setDislike(t.tid, true) }
            if (r == null) { _opFailure.value = true; return@launch }
            load()
            onUndoReady(r.first, r.second)
        }
    }

    /** 取消不喜欢（不喜欢段长按/恢复）；wasFavorite 恢复标记前是否收藏。 */
    fun undoDislike(tid: String, wasFavorite: Boolean) {
        viewModelScope.launch {
            if (wasFavorite) repo.setFavorite(tid, true) else repo.setDislike(tid, false)
            load()
        }
    }

    /** 通用撤销（快照+版本）：用于更多菜单里收藏/不喜欢的撤销。 */
    fun undoMark(tid: String, snap: MarkSnapshot, version: Int) {
        viewModelScope.launch {
            when (markOps.undo(tid, snap, version)) {
                MarkOps.UndoResult.OK -> load()
                MarkOps.UndoResult.STALE -> {}
                MarkOps.UndoResult.FAILED -> _opFailure.value = true
            }
        }
    }

    /** 已读完绝对 setter。 */
    fun setFinished(tid: String, target: Boolean) {
        viewModelScope.launch {
            val r = markOps.apply(tid) { repo.setFinished(tid, target) }
            if (r == null) _opFailure.value = true
            load()
        }
    }
}

class FavoritesVmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(m: Class<T>): T = FavoritesViewModel(BookRepository(db)) as T
}
