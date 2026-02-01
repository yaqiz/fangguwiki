package com.example.fangguwiki

import androidx.room.TypeConverter

class HeritageTypeConverters {
    @TypeConverter
    fun fromHeritageType(type: HeritageType): String = type.name

    @TypeConverter
    fun toHeritageType(value: String): HeritageType = HeritageType.valueOf(value)

    @TypeConverter
    fun fromHeritageLevel(level: HeritageLevel): String = level.name

    @TypeConverter
    fun toHeritageLevel(value: String): HeritageLevel = HeritageLevel.valueOf(value)
}
