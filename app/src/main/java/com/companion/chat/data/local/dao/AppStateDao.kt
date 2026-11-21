package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.companion.chat.data.local.entity.AppStateEntity
import kotlinx.coroutines.flow.Flow

/** 全局状态 DAO。任务 1.1 */
@Dao
interface AppStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: AppStateEntity)

    @Query("SELECT value FROM app_state WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_state")
    fun observeAll(): Flow<List<AppStateEntity>>

    @Query("SELECT * FROM app_state")
    suspend fun getAll(): List<AppStateEntity>

    @Query("DELETE FROM app_state WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM app_state")
    suspend fun deleteAll()
}
