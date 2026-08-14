package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.room.domain.RoomImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomImageRepository extends JpaRepository<RoomImage, UUID> {

    List<RoomImage> findAllByRoomIdOrderBySortOrderAsc(UUID roomId);
}
