package com.family.dialer.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ContactDao {

    @Query("SELECT * FROM contacts ORDER BY sortOrder ASC, id ASC")
    fun getAll(): LiveData<List<Contact>>

    @Query("SELECT * FROM contacts ORDER BY sortOrder ASC, id ASC")
    fun getAllSync(): List<Contact>

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun getById(id: Long): Contact?

    @Insert
    fun insert(contact: Contact): Long

    @Update
    fun update(contact: Contact)

    @Delete
    fun delete(contact: Contact)

    @Query("DELETE FROM contacts WHERE id = :id")
    fun deleteById(id: Long)
}
