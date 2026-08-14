package com.eduplatform.eduplatform_backend.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One per failed field in a validation error response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldErrorItem(String field, String code, String message, Object rejectedValue) {}
