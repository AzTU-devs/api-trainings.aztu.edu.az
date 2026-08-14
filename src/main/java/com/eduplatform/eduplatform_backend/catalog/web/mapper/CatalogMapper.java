package com.eduplatform.eduplatform_backend.catalog.web.mapper;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryDto;
import com.eduplatform.eduplatform_backend.catalog.web.dto.TagDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CatalogMapper {

    @Mapping(target = "parentId", source = "parent.id")
    CategoryDto toCategoryDto(Category category);

    TagDto toTagDto(Tag tag);
}
