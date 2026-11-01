package com.foodmap.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, String> fieldErrors;

    public static ErrorResponse of(HttpStatus httpStatus, String message, String path) {
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(httpStatus.value())
            .error(httpStatus.getReasonPhrase())
            .message(message)
            .path(path)
            .build();
    }

    public static ErrorResponse ofValidation(String message, String path, Map<String, String> fieldErrors) {
        return ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
            .message(message)
            .path(path)
            .fieldErrors(fieldErrors)
            .build();
    }
}
