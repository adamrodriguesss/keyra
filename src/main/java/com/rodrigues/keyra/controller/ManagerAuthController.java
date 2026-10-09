package com.rodrigues.keyra.controller;

import com.rodrigues.keyra.dto.LoginRequest;
import com.rodrigues.keyra.dto.ManagerResponse;
import com.rodrigues.keyra.dto.RefreshTokenRequest;
import com.rodrigues.keyra.dto.TokenResponse;
import com.rodrigues.keyra.service.ManagerRealm;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/manager")
public class ManagerAuthController {

    private final ManagerRealm managers;

    public ManagerAuthController(ManagerRealm managers) {
        this.managers = managers;
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return managers.login(request);
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return managers.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        managers.logout(request.refreshToken());
    }

    @GetMapping("/me")
    public ManagerResponse me(@AuthenticationPrincipal Jwt jwt) {
        return ManagerResponse.from(managers.account(jwt));
    }
}
