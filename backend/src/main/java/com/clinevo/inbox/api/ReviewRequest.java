package com.clinevo.inbox.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ReviewRequest(
        @NotBlank String action,
        String overrideCategory,
        String note,
        @NotBlank String reviewer,
        List<@Valid FactOverride> factOverrides
) {
    public ReviewRequest {
        factOverrides = factOverrides == null ? List.of() : List.copyOf(factOverrides);
    }
}
