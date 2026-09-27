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

    // Everything here comes from the booking's own columns and read-only subselects, never from
    // the lazy room / tutor / course proxies: see RoomBooking for why.
    @Mapping(target = "offlineCourseId", expression = "java(booking.getOfflineCourse() == null ? null : booking.getOfflineCourse().getCourseId())")
    @Mapping(target = "tutorId", expression = "java(booking.getTutor() == null ? null : booking.getTutor().getId())")
    RoomBookingDto toBookingDto(RoomBooking booking);

    default List<UUID> toImageIds(java.util.Set<RoomImage> images) {
        if (images == null) return List.of();
        return images.stream()
                .sorted(java.util.Comparator.comparingInt(RoomImage::getSortOrder))
                .map(img -> img.getMedia().getId())
                .toList();
    }
}
