package com.clinevo.inbox.api;

import jakarta.validation.constraints.NotNull;

public record FactOverride(@NotNull Long factId, String newValue) {}
