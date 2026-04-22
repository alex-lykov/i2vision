package com.example.repository

import com.example.models.User

class UserRepository {
    fun findById(id: String): User? = null
    fun save(user: User): User = user
}
