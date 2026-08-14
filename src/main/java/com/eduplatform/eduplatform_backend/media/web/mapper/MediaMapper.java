package com.eduplatform.eduplatform_backend.media.web.mapper;

import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.web.dto.MediaFileDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface MediaMapper {

    MediaFileDto toDto(MediaFile media);
}
