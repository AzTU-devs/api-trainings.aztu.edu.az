package com.eduplatform.eduplatform_backend.video.web.dto;

import java.util.UUID;

public record VideoInitResponse(String uploadUrl, UUID videoId) {}
