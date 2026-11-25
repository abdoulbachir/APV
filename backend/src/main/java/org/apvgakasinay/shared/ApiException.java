package org.apvgakasinay.shared;

import org.springframework.http.HttpStatus;

/** An error the client caused, reported with the error envelope of the contract. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String fieldName;
    private final String fieldCode;

    private ApiException(HttpStatus status, String code, String message, String fieldName, String fieldCode) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldName = fieldName;
        this.fieldCode = fieldCode;
    }

    /** 400 validation_error naming one query parameter. */
    public static ApiException invalid(String field, String fieldCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_error", message, field, fieldCode);
    }

    /** 404 case_not_found. Deliberately the same for a draft and for a code that does not exist. */
    public static ApiException caseNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "case_not_found", "Case not found", null, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String fieldName() {
        return fieldName;
    }

    public String fieldCode() {
        return fieldCode;
    }
}
