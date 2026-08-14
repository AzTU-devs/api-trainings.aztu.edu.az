package com.eduplatform.eduplatform_backend.course.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.course.service.CourseContentService;
import com.eduplatform.eduplatform_backend.course.web.dto.LessonDto;
import com.eduplatform.eduplatform_backend.course.web.dto.LessonUpsertRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.ModuleDto;
import com.eduplatform.eduplatform_backend.course.web.dto.ModuleUpsertRequest;
import com.eduplatform.eduplatform_backend.course.web.mapper.CourseMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Tutor course-content management. All routes require {@code course:update_own};
 * the service additionally enforces that the caller owns the parent course.
 */
@RestController
@RequestMapping("/api/portal")
@Tag(name = "Portal — Course content (modules & lessons)")
@PreAuthorize("hasAuthority('course:update_own')")
public class CourseContentController {

    private final CourseContentService service;
    private final CourseMapper mapper;

    public CourseContentController(CourseContentService service, CourseMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    // ---- modules ----

    @GetMapping("/courses/{courseId}/modules")
    @Operation(summary = "List modules of a course")
    public ApiResponse<List<ModuleDto>> listModules(@PathVariable UUID courseId) {
        return ApiResponse.ok(service.listModules(courseId).stream().map(mapper::toModuleDto).toList());
    }

    @PostMapping("/courses/{courseId}/modules")
    @Operation(summary = "Add a module to a course")
    public ResponseEntity<ApiResponse<ModuleDto>> addModule(@PathVariable UUID courseId,
                                                            @Valid @RequestBody ModuleUpsertRequest req,
                                                            @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toModuleDto(service.addModule(me.userId(), courseId, req))));
    }

    @PutMapping("/modules/{moduleId}")
    @Operation(summary = "Update a module")
    public ApiResponse<ModuleDto> updateModule(@PathVariable UUID moduleId,
                                               @Valid @RequestBody ModuleUpsertRequest req,
                                               @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toModuleDto(service.updateModule(me.userId(), moduleId, req)));
    }

    @DeleteMapping("/modules/{moduleId}")
    @Operation(summary = "Delete a module")
    public ResponseEntity<Void> deleteModule(@PathVariable UUID moduleId, @CurrentUser AuthenticatedPrincipal me) {
        service.deleteModule(me.userId(), moduleId);
        return ResponseEntity.noContent().build();
    }

    // ---- lessons ----

    @GetMapping("/modules/{moduleId}/lessons")
    @Operation(summary = "List lessons of a module")
    public ApiResponse<List<LessonDto>> listLessons(@PathVariable UUID moduleId) {
        return ApiResponse.ok(service.listLessons(moduleId).stream().map(mapper::toLessonDto).toList());
    }

    @PostMapping("/modules/{moduleId}/lessons")
    @Operation(summary = "Add a lesson to a module")
    public ResponseEntity<ApiResponse<LessonDto>> addLesson(@PathVariable UUID moduleId,
                                                            @Valid @RequestBody LessonUpsertRequest req,
                                                            @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toLessonDto(service.addLesson(me.userId(), moduleId, req))));
    }

    @PutMapping("/lessons/{lessonId}")
    @Operation(summary = "Update a lesson")
    public ApiResponse<LessonDto> updateLesson(@PathVariable UUID lessonId,
                                               @Valid @RequestBody LessonUpsertRequest req,
                                               @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toLessonDto(service.updateLesson(me.userId(), lessonId, req)));
    }

    @DeleteMapping("/lessons/{lessonId}")
    @Operation(summary = "Delete a lesson")
    public ResponseEntity<Void> deleteLesson(@PathVariable UUID lessonId, @CurrentUser AuthenticatedPrincipal me) {
        service.deleteLesson(me.userId(), lessonId);
        return ResponseEntity.noContent().build();
    }
}
