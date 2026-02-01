package com.example.fangguwiki

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HeritageDao {
    @Query("SELECT * FROM heritage_pins")
    fun getAllPins(): Flow<List<HeritagePin>>

    @Insert
    suspend fun insertPin(pin: HeritagePin)

    @Update
    suspend fun updatePin(pin: HeritagePin)

    @Delete
    suspend fun deletePin(pin: HeritagePin)

    @Query("SELECT * FROM heritage_pins WHERE type = :type")
    fun getPinsByType(type: HeritageType): Flow<List<HeritagePin>>
}
