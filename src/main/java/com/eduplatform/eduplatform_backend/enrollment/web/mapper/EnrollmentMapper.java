package com.eduplatform.eduplatform_backend.enrollment.web.mapper;

import com.eduplatform.eduplatform_backend.enrollment.domain.AttendanceRecord;
import com.eduplatform.eduplatform_backend.enrollment.domain.Enrollment;
import com.eduplatform.eduplatform_backend.enrollment.domain.LessonProgress;
import com.eduplatform.eduplatform_backend.enrollment.domain.OfflineSession;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.AttendanceRecordDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.EnrollmentDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.LessonProgressDto;
import com.eduplatform.eduplatform_backend.enrollment.web.dto.OfflineSessionDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EnrollmentMapper {

    @Mapping(target = "userId",      source = "user.id")
    @Mapping(target = "courseId",    source = "course.id")
    @Mapping(target = "courseTitle", source = "course.title")
    EnrollmentDto toDto(Enrollment e);

    @Mapping(target = "lessonId", source = "lesson.id")
    LessonProgressDto toLessonProgressDto(LessonProgress lp);

    @Mapping(target = "offlineCourseId", source = "offlineCourse.courseId")
    OfflineSessionDto toSessionDto(OfflineSession s);

    @Mapping(target = "sessionId",    source = "session.id")
    @Mapping(target = "enrollmentId", source = "enrollment.id")
    AttendanceRecordDto toAttendanceDto(AttendanceRecord r);
}
