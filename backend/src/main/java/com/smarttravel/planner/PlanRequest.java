package com.smarttravel.planner;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

public record PlanRequest(
        @NotBlank String city,
        @Min(1) @Max(10) int days,
        @NotNull LocalDate startDate,
        List<String> preferences,
        String pace,
        String transport,
        @Min(500) @Max(200000) Integer budget,
        String freeText) {}
