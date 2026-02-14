package com.family.dialer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val color: Int, // 头像背景色 (Color int)
    val sortOrder: Int = 0
)
