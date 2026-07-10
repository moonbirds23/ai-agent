package com.zzp.imageretrievalmcp.contract;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Generic MCP response wrapper with schema versioning, request correlation,
 * and structured error/warning reporting.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record McpCommonResponse<T>(
    String schemaVersion,
    String requestId,
    T data,
    List<McpError> errors,
    List<String> warnings
) {
    public McpCommonResponse {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = "1.0";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpError(
        String code,
        String message,
        String detail
    ) {}
}
