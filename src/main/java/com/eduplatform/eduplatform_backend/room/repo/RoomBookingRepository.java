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

    Page<RoomBooking> findAllByTutorId(UUID tutorId, Pageable pageable);

    /** A tutor's own bookings (history), resolved by the owning user id, newest first. */
    @Query("select b from RoomBooking b where b.tutor.user.id = :userId order by b.startsAt desc")
    Page<RoomBooking> findAllByTutorUser(@Param("userId") UUID userId, Pageable pageable);

    long countByStatus(BookingStatus status);

    @Query("select count(b) from RoomBooking b where b.tutor.user.id = :userId and b.status = :status")
    long countByTutorUserAndStatus(@Param("userId") UUID userId, @Param("status") BookingStatus status);

    Page<RoomBooking> findAllByRoomId(UUID roomId, Pageable pageable);

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
