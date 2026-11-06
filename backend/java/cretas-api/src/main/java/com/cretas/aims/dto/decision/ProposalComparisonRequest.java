package com.cretas.aims.dto.decision;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** All facts are supplied by the caller; no business record is loaded or certified. */
public record ProposalComparisonRequest(
        @NotBlank @Size(max = 64) String comparisonBasisId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 32) String costUnit,
        @NotBlank @Size(max = 32) String resourceUnit,
        @NotNull OffsetDateTime windowStart,
        @NotNull OffsetDateTime windowEnd,
        OffsetDateTime requiredBy,
        @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal availableResource,
        @NotNull @Size(min = 2, max = 20) List<@NotNull @Valid Candidate> candidates) {

    public record Candidate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
            @NotBlank @Size(max = 64) String comparisonBasisId,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotBlank @Size(max = 32) String costUnit,
            @NotBlank @Size(max = 32) String resourceUnit,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal cost,
            OffsetDateTime completionTime,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal requiredResource) { }
}
