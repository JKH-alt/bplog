package com.bplog.holter.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val systolic: Int,
    val diastolic: Int,
    val map: Int,
    val hr: Int,
    val status: Int,
    val note: String? = null
)
