package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.room.domain.RoomAvailabilitySlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomAvailabilitySlotRepository extends JpaRepository<RoomAvailabilitySlot, UUID> {

    List<RoomAvailabilitySlot> findAllByRoomIdOrderByDayOfWeekAscStartTimeAsc(UUID roomId);
}
