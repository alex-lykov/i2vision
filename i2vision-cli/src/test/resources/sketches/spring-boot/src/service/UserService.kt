package com.example.service

import com.example.repository.UserRepository
import com.example.models.User

class UserService(
    private val repository: UserRepository
) {
    fun findById(id: String): User? = repository.findById(id)
    fun create(request: CreateUserRequest): User = repository.save(request.toUser())
}

data class CreateUserRequest(val name: String, val email: String) {
    fun toUser() = User(id = "", name, email)
}
