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

    // Preserve the original five-argument constructor used by existing tests and callers.
    public ReviewRequest(
            String action,
            String overrideCategory,
            String note,
            String reviewer,
            List<FactOverride> factOverrides
    ) {
        this(action, overrideCategory, List.of(), note, reviewer, factOverrides);
    }
}
