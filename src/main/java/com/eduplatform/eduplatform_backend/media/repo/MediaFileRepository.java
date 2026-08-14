package com.eduplatform.eduplatform_backend.media.repo;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, UUID> {

    List<MediaFile> findAllByOwnerUserId(UUID ownerUserId);

    List<MediaFile> findAllByStatus(MediaStatus status);

    /** A tutor's uploaded video assets (mime type video/*), newest first. */
    @Query("select m from MediaFile m where m.ownerUserId = :owner and m.mimeType like 'video/%' order by m.createdAt desc")
    Page<MediaFile> findVideosByOwner(@Param("owner") UUID owner, Pageable pageable);
}
