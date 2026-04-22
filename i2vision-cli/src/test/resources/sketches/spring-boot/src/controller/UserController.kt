package com.example.controller

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: com.example.service.UserService
) {
    @GetMapping("/{id}")
    fun getUser(@PathVariable id: String) = userService.findById(id)
    
    @PostMapping
    fun createUser(@RequestBody request: CreateUserRequest) = userService.create(request)
}
