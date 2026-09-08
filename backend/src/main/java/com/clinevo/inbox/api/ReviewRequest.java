package com.clinevo.inbox.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ReviewRequest(
        @NotBlank String action,
        String overrideCategory,
        List<String> overrideCategories,
        String note,
        String reviewer,
        List<@Valid FactOverride> factOverrides
) {
    public ReviewRequest {
        overrideCategories = overrideCategories == null ? List.of() : List.copyOf(overrideCategories);
        factOverrides = factOverrides == null ? List.of() : List.copyOf(factOverrides);
    }
}
