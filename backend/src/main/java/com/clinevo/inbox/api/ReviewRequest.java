package com.clinevo.inbox.api;

import jakarta.validation.constraints.NotBlank;

public record ReviewRequest(
        @NotBlank String action,
        String overrideCategory,
        String note,
        @NotBlank String reviewer
) {}
