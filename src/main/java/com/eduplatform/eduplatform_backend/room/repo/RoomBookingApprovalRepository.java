package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.room.domain.RoomBookingApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomBookingApprovalRepository extends JpaRepository<RoomBookingApproval, UUID> {

    List<RoomBookingApproval> findAllByBookingIdOrderByDecidedAtDesc(UUID bookingId);
}
