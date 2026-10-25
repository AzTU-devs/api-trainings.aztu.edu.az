package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RoomBookingRepository extends JpaRepository<RoomBooking, UUID> {

    Page<RoomBooking> findAllByStatus(BookingStatus status, Pageable pageable);

    /**
     * The admin's queue of single bookings. Occurrences of a series are left out: they are decided
     * with their series, and a two-month request would otherwise bury the queue under its rows.
     */
    @Query("select b from RoomBooking b where b.status = :status and b.seriesId is null")
    Page<RoomBooking> findSinglesByStatus(@Param("status") BookingStatus status, Pageable pageable);

    Page<RoomBooking> findAllByTutorId(UUID tutorId, Pageable pageable);

    /**
     * A tutor's own single bookings (history), resolved by the owning user id, newest first.
     * Occurrences of a series are listed with their series instead.
     */
    @Query("select b from RoomBooking b where b.tutor.user.id = :userId and b.seriesId is null order by b.startsAt desc")
    Page<RoomBooking> findAllByTutorUser(@Param("userId") UUID userId, Pageable pageable);

    /** A series' occurrences in date order. */
    List<RoomBooking> findAllBySeriesIdOrderByStartsAtAsc(UUID seriesId);

    long countByStatus(BookingStatus status);

    /** Single bookings in a status; occurrences are counted through their series. */
    long countByStatusAndSeriesIdIsNull(BookingStatus status);

    @Query("select count(b) from RoomBooking b where b.tutor.user.id = :userId and b.status = :status")
    long countByTutorUserAndStatus(@Param("userId") UUID userId, @Param("status") BookingStatus status);

    Page<RoomBooking> findAllByRoomId(UUID roomId, Pageable pageable);

    /** Whether any booking, in any status, is on record for the room. */
    boolean existsByRoomId(UUID roomId);

    /**
     * Pre-check overlap before insert; the DB exclusion constraint is the source of truth
     * but this gives a friendlier error message and avoids burning a transaction.
     */
    @Query("""
           select b from RoomBooking b
           where b.room.id = :roomId
             and b.status = 'APPROVED'
             and b.startsAt < :endsAt
             and b.endsAt   > :startsAt
           """)
    List<RoomBooking> findOverlappingApproved(@Param("roomId") UUID roomId,
                                              @Param("startsAt") Instant startsAt,
                                              @Param("endsAt") Instant endsAt);
}
