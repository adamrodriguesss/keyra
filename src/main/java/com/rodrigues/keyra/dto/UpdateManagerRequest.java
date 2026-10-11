package com.rodrigues.keyra.dto;

import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.entity.ManagerRole;

/** Partial update: a null field is left unchanged. */
public record UpdateManagerRequest(ManagerRole role, AccountStatus status) {}
