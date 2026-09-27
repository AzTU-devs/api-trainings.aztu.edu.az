package com.eduplatform.eduplatform_backend.analytics.service;

import com.eduplatform.eduplatform_backend.analytics.web.dto.AdminDashboardDto;
import com.eduplatform.eduplatform_backend.analytics.web.dto.AnalyticsOverviewDto;
import com.eduplatform.eduplatform_backend.analytics.web.dto.TutorDashboardDto;
import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Read-only aggregation backing the admin analytics overview + role dashboards. */
@Service
public class AnalyticsService {

    private final UserRepository users;
    private final TutorProfileRepository tutors;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final RoomRepository rooms;
    private final RoomBookingRepository bookings;

    /**
     * The university's zone. Days and months are Baku's: in UTC an enrolment made between midnight
     * and 04:00 local time counted on the previous day, or the previous month.
     */
    private final ZoneId zone;

    public AnalyticsService(UserRepository users, TutorProfileRepository tutors, CourseRepository courses,
                            EnrollmentRepository enrollments, RoomRepository rooms, RoomBookingRepository bookings,
                            @Value("${app.timezone:Asia/Baku}") String zone) {
        this.users = users;
        this.tutors = tutors;
        this.courses = courses;
        this.enrollments = enrollments;
        this.rooms = rooms;
        this.bookings = bookings;
        this.zone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true)
    public AnalyticsOverviewDto overview(String range) {
        int days = switch (range == null ? "30d" : range) {
            case "7d" -> 7;
            case "90d" -> 90;
            default -> 30;
        };
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        Instant monthStart = LocalDate.now(zone).withDayOfMonth(1).atStartOfDay(zone).toInstant();
        double capacityHoursPerRoom = days * 12.0; // 12 bookable hours/day baseline

        List<AnalyticsOverviewDto.TrendPoint> trend = enrollments.findEnrollmentTrend(since, zone.getId()).stream()
                .map(p -> new AnalyticsOverviewDto.TrendPoint(p.getDay(), p.getCnt()))
                .toList();

        List<AnalyticsOverviewDto.TopCourse> topCourses =
                courses.findTopCourses(CourseStatus.PUBLISHED, PageRequest.of(0, 5)).stream()
                        .map(c -> new AnalyticsOverviewDto.TopCourse(c.getId(), c.getTitle(), c.getEnrolledCount()))
                        .toList();

        List<AnalyticsOverviewDto.TopCategory> topCategories =
                courses.findTopCategories(CourseStatus.PUBLISHED, PageRequest.of(0, 5)).stream()
                        .map(c -> new AnalyticsOverviewDto.TopCategory(c.getId(), c.getName(), c.getCourseCount()))
                        .toList();

        List<AnalyticsOverviewDto.RoomUtil> roomUtil =
                rooms.findRoomUtilization(since, PageRequest.of(0, 8)).stream()
                        .map(r -> new AnalyticsOverviewDto.RoomUtil(
                                r.getRoomId(), r.getRoomName(),
                                round1(Math.min(100.0, capacityHoursPerRoom <= 0 ? 0
                                        : r.getBookedHours() / capacityHoursPerRoom * 100.0))))
                        .toList();

        return new AnalyticsOverviewDto(
                enrollments.countDistinctStudentsByStatus(EnrollmentStatus.ACTIVE),
                tutors.countWithLiveAccountByApprovalStatus(TutorApprovalStatus.APPROVED),
                courses.countByStatus(CourseStatus.PUBLISHED),
                enrollments.countByEnrolledAtAfter(monthStart),
                trend, topCourses, topCategories, roomUtil);
    }

    @Transactional(readOnly = true)
    public AdminDashboardDto adminDashboard() {
        return new AdminDashboardDto(
                users.count(),
                tutors.countWithLiveAccountByApprovalStatus(TutorApprovalStatus.PENDING),
                courses.countByStatus(CourseStatus.IN_REVIEW),
                courses.countByStatus(CourseStatus.PUBLISHED),
                rooms.count(),
                bookings.countByStatus(BookingStatus.PENDING),
                enrollments.count());
    }

    @Transactional(readOnly = true)
    public TutorDashboardDto tutorDashboard(UUID tutorUserId) {
        return new TutorDashboardDto(
                courses.countByTutorUser(tutorUserId),
                courses.countByTutorUserAndStatus(tutorUserId, CourseStatus.PUBLISHED),
                enrollments.countDistinctStudentsByTutorUser(tutorUserId),
                courses.countByTutorUserAndStatus(tutorUserId, CourseStatus.IN_REVIEW),
                bookings.countByTutorUserAndStatus(tutorUserId, BookingStatus.APPROVED));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
