package com.eduplatform.eduplatform_backend.course.web.dto;

public record OnlineDetailsDto(
        int totalVideoSeconds,
        boolean hasCertificate,
        boolean dripEnabled
) {}
