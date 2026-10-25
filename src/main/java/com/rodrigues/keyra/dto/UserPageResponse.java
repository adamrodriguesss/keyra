package com.rodrigues.keyra.dto;

import java.util.List;

public record UserPageResponse(List<UserResponse> items, int page, int size, long total) {}
