package com.eduplatform.eduplatform_backend.catalog.web;

import com.eduplatform.eduplatform_backend.catalog.service.TagService;
import com.eduplatform.eduplatform_backend.catalog.web.dto.TagDto;
import com.eduplatform.eduplatform_backend.catalog.web.dto.TagUpsertRequest;
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
@Tag(name = "Tags")
public class TagController {

    private final TagService service;
    private final CatalogMapper mapper;

    public TagController(TagService service, CatalogMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping("/api/public/tags")
    @Operation(summary = "List all tags", security = {})
    public ApiResponse<List<TagDto>> all() {
        return ApiResponse.ok(service.all().stream().map(mapper::toTagDto).toList());
    }

    @PostMapping("/api/admin/tags")
    @PreAuthorize("hasAuthority('tag:manage')")
    @Operation(summary = "Create a tag")
    public ResponseEntity<ApiResponse<TagDto>> create(@Valid @RequestBody TagUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toTagDto(service.create(req.slug(), req.name()))));
    }

    @PutMapping("/api/admin/tags/{id}")
    @PreAuthorize("hasAuthority('tag:manage')")
    @Operation(summary = "Update a tag")
    public ApiResponse<TagDto> update(@PathVariable UUID id, @Valid @RequestBody TagUpsertRequest req) {
        return ApiResponse.ok(mapper.toTagDto(service.update(id, req.slug(), req.name())));
    }

    @DeleteMapping("/api/admin/tags/{id}")
    @PreAuthorize("hasAuthority('tag:manage')")
    @Operation(summary = "Delete a tag")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
