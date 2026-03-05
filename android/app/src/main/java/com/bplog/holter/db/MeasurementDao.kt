package com.bplog.holter.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: Measurement)

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Measurement>>

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE measurements SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)
}
