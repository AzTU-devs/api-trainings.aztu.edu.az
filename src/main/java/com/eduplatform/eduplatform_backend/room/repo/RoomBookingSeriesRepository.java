package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.room.domain.RoomBookingSeries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface RoomBookingSeriesRepository extends JpaRepository<RoomBookingSeries, UUID> {

    /** A tutor's own series, newest request first. The id breaks ties so pages never overlap. */
    @Query("select s from RoomBookingSeries s where s.tutor.user.id = :userId order by s.createdAt desc, s.id desc")
    Page<RoomBookingSeries> findAllByTutorUser(@Param("userId") UUID userId, Pageable pageable);

    /** The admin queue: oldest request first, so nobody waits behind newer ones. */
    @Query("select s from RoomBookingSeries s where s.status = :status order by s.createdAt asc, s.id asc")
    Page<RoomBookingSeries> findAllByStatusOldestFirst(@Param("status") BookingStatus status, Pageable pageable);

    long countByStatus(BookingStatus status);
}
