package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.room.domain.RoomImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomImageRepository extends JpaRepository<RoomImage, UUID> {

    List<RoomImage> findAllByRoomIdOrderBySortOrderAsc(UUID roomId);

    /** Whether the file is a photo of a room that has not been deleted. */
    @Query("""
           select case when count(i) > 0 then true else false end
           from RoomImage i join i.room r
           where i.media.id = :mediaId
           """)
    boolean isImageOfLiveRoom(@Param("mediaId") UUID mediaId);
}
