package com.eduplatform.eduplatform_backend.room.web.mapper;

import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomAvailabilitySlot;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import com.eduplatform.eduplatform_backend.room.domain.RoomImage;
import com.eduplatform.eduplatform_backend.room.web.dto.AvailabilitySlotDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomBookingDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface RoomMapper {

    @Mapping(target = "imageMediaIds", expression = "java(toImageIds(room.getImages()))")
    RoomDto toDto(Room room);

    AvailabilitySlotDto toAvailabilityDto(RoomAvailabilitySlot slot);

    @Mapping(target = "roomId",   source = "room.id")
    @Mapping(target = "roomName", source = "room.name")
    @Mapping(target = "offlineCourseId", source = "offlineCourse.courseId")
    @Mapping(target = "tutorId", source = "tutor.id")
    RoomBookingDto toBookingDto(RoomBooking booking);

    default List<UUID> toImageIds(java.util.Set<RoomImage> images) {
        if (images == null) return List.of();
        return images.stream()
                .sorted(java.util.Comparator.comparingInt(RoomImage::getSortOrder))
                .map(img -> img.getMedia().getId())
                .toList();
    }
}
