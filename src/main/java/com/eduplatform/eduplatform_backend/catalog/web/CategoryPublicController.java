package com.eduplatform.eduplatform_backend.catalog.web;

import com.eduplatform.eduplatform_backend.catalog.service.CategoryService;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryDto;
import com.eduplatform.eduplatform_backend.catalog.web.mapper.CatalogMapper;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/categories")
@Tag(name = "Public catalog")
public class CategoryPublicController {

    private final CategoryService service;
    private final CatalogMapper mapper;

    public CategoryPublicController(CategoryService service, CatalogMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List top-level categories", security = {})
    public ApiResponse<List<CategoryDto>> roots() {
        return ApiResponse.ok(service.roots().stream().map(mapper::toCategoryDto).toList());
    }

    @GetMapping("/{id}/children")
    @Operation(summary = "List immediate sub-categories", security = {})
    public ApiResponse<List<CategoryDto>> children(@PathVariable UUID id) {
        return ApiResponse.ok(service.children(id).stream().map(mapper::toCategoryDto).toList());
    }
}
