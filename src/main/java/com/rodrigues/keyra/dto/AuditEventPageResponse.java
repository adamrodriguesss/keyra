package com.rodrigues.keyra.dto;

import java.util.List;

public record AuditEventPageResponse(List<AuditEventResponse> items, int page, int size, long total) {}
