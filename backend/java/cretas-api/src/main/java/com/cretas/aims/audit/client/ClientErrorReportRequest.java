package com.cretas.aims.audit.client;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bounded browser telemetry. It deliberately has no message, payload, URL, or headers field. */
public record ClientErrorReportRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        String eventId,

        @NotBlank
        @Pattern(regexp = "vue|window_error|unhandled_rejection|request_failure")
        String category,

        @NotBlank
        @Size(max = 256)
        @Pattern(regexp = "/[A-Za-z0-9_./:@%~-]{0,255}")
        String route,

        @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD")
        String method,

        @Min(0)
        @Max(599)
        Integer status,

        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
        String errorCode,

        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
        String parentCorrelationId,

        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")
        String source,

        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9_.-]{1,128}")
        String file,

        @Min(0)
        @Max(100000)
        Integer line,

        @Min(0)
        @Max(100000)
        Integer column) {
}
