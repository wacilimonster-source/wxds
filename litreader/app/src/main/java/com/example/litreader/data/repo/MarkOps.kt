package com.example.litreader.data.repo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 标记快照：撤销按原值恢复（含时间戳），收藏/不喜欢互斥由此一并还原。 */
data class MarkSnapshot(
    val tid: String,
    val favorite: Boolean,
    val favoriteAt: Long,
    val dislike: Boolean,
    val dislikeAt: Long
)

/**
 * 标记写入与撤销的会话一致性：
 * - 逐 tid 版本号：撤销只在版本仍匹配时生效，被后续操作取代的旧撤销静默忽略；
 * - Mutex 串行写入：同一时刻只有一个标记事务，写失败返回 null（UI 复原并提示）。
 */
class MarkOps(private val repo: BookRepository) {

    enum class UndoResult { OK, STALE, FAILED }

    private val versions = HashMap<String, Int>()
    private val mutex = Mutex()

    private fun nextVersion(tid: String): Int =
        (versions.getOrDefault(tid, 0)) + 1

    /**
     * 执行一次标记写入：先取快照，写入成功返回 (快照, 新版本) 供撤销使用；
     * 写入失败返回 null（不产生版本号，UI 复原并提示「操作未完成」）。
     */
    suspend fun apply(tid: String, write: suspend () -> Unit): Pair<MarkSnapshot, Int>? =
        mutex.withLock {
            val snap = repo.markSnapshot(tid) ?: MarkSnapshot(tid, false, 0, false, 0)
            try {
                write()
                val v = nextVersion(tid)
                versions[tid] = v
                snap to v
            } catch (e: Exception) {
                null
            }
        }

    /** 撤销：版本匹配才恢复快照原值；恢复本身也是一次新操作（版本继续前进）。 */
    suspend fun undo(tid: String, snap: MarkSnapshot, expectVersion: Int): UndoResult =
        mutex.withLock {
            if (versions.getOrDefault(tid, 0) != expectVersion) return@withLock UndoResult.STALE
            try {
                repo.restoreMark(snap)
                versions[tid] = nextVersion(tid)
                UndoResult.OK
            } catch (e: Exception) {
                UndoResult.FAILED
            }
        }
}
