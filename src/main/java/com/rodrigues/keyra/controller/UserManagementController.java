package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.UpdateUserRequest;
import com.rodrigues.keyra.dto.UserResponse;
import com.rodrigues.keyra.service.UserRealm;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Open to any manager (ADMIN or MANAGER). */
@RestController
@RequestMapping("/api/manager/users")
public class UserManagementController {

    private final UserRealm users;

    public UserManagementController(UserRealm users) {
        this.users = users;
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(users.setStatus(id, request.status()));
    }
}
