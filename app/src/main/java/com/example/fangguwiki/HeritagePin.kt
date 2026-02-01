package com.example.fangguwiki

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class HeritageType {
    TOWER,            // 塔
    GROTTO,           // 石刻/石窟
    ANCIENT_BUILDING, // 古建
}

enum class HeritageLevel {
    DEFAULT,    // 默认
    PROVINCIAL, // 省保
    NATIONAL,   // 国保
    WORLD       // 世遗
}

@Entity(tableName = "heritage_pins")
data class HeritagePin(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: HeritageType,
    val level: HeritageLevel,
    val latitude: Double,
    val longitude: Double,
    val description: String,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
