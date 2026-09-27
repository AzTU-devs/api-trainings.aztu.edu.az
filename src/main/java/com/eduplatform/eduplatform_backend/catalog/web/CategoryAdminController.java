package com.eduplatform.eduplatform_backend.catalog.web;

import com.eduplatform.eduplatform_backend.catalog.service.CategoryService;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryDto;
import com.eduplatform.eduplatform_backend.catalog.web.dto.CategoryUpsertRequest;
import com.eduplatform.eduplatform_backend.catalog.web.mapper.CatalogMapper;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/categories")
@Tag(name = "Admin — Categories")
@PreAuthorize("hasAuthority('category:manage')")
public class CategoryAdminController {

    private final CategoryService service;
    private final CatalogMapper mapper;

    public CategoryAdminController(CategoryService service, CatalogMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List every category, flat, hidden ones and sub-categories included",
            description = "Each entry carries parentId, so the tree can be built client-side.")
    public ApiResponse<List<CategoryDto>> list() {
        return ApiResponse.ok(service.all().stream().map(mapper::toCategoryDto).toList());
    }

    @PostMapping
    @Operation(summary = "Create a category")
    public ResponseEntity<ApiResponse<CategoryDto>> create(@Valid @RequestBody CategoryUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toCategoryDto(service.create(req))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a category")
    public ApiResponse<CategoryDto> update(@PathVariable UUID id, @Valid @RequestBody CategoryUpsertRequest req) {
        return ApiResponse.ok(mapper.toCategoryDto(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a category")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
