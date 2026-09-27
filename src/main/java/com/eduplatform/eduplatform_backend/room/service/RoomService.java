package com.eduplatform.eduplatform_backend.room.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.RoomStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.room.domain.Room;
import com.eduplatform.eduplatform_backend.room.domain.RoomImage;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import com.eduplatform.eduplatform_backend.room.repo.RoomRepository;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomUpsertRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RoomService {

    private final RoomRepository repo;
    private final MediaFileRepository media;
    private final RoomBookingRepository bookings;
    private final AuditService audit;

    public RoomService(RoomRepository repo, MediaFileRepository media, RoomBookingRepository bookings,
                       AuditService audit) {
        this.repo = repo;
        this.media = media;
        this.bookings = bookings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Room get(UUID id) {
        Room room = repo.findById(id).orElseThrow(
                () -> Errors.notFound("ROOM_NOT_FOUND", "Room does not exist"));
        room.getImages().size();   // initialise lazy collection before the session closes
        return room;
    }

    @Transactional(readOnly = true)
    public Page<Room> list(Pageable pageable) {
        Page<Room> page = repo.findAll(pageable);
        // Initialise the lazy images collection within the transaction so the
        // web mapper can read it after the session closes (open-in-view=false).
        // @BatchSize on Room.images keeps this from N+1-ing.
        page.forEach(r -> r.getImages().size());
        return page;
    }

    /** Tutor-facing catalog: only rooms that can actually be borrowed. */
    @Transactional(readOnly = true)
    public Page<Room> listAvailable(Pageable pageable) {
        Page<Room> page = repo.findAllByStatus(RoomStatus.AVAILABLE, pageable);
        page.forEach(r -> r.getImages().size());
        return page;
    }

    @Transactional
    public Room create(RoomUpsertRequest req) {
        requireUniqueLocation(req.building(), req.roomNumber(), null);
        Room r = Room.builder()
                .name(req.name())
                .roomNumber(req.roomNumber())
                .building(req.building())
                .capacity(req.capacity())
                .description(req.description())
                .status(req.status() == null ? RoomStatus.AVAILABLE : req.status())
                .hourlyRate(req.hourlyRate())
                .currency(req.currency())
                .build();
        r.setId(UUID.randomUUID());
        applyImages(r, req.imageMediaIds());
        Room saved = repo.save(r);
        audit.record(AuditService.Actions.CREATE, "ROOM", saved.getId(), null, auditableFields(saved));
        return saved;
    }

    @Transactional
    public Room update(UUID id, RoomUpsertRequest req) {
        Room r = get(id);
        Map<String, Object> before = auditableFields(r);
        requireUniqueLocation(req.building(), req.roomNumber(), id);
        r.setName(req.name());
        r.setRoomNumber(req.roomNumber());
        r.setBuilding(req.building());
        r.setCapacity(req.capacity());
        r.setDescription(req.description());
        if (req.status() != null) r.setStatus(req.status());
        r.setHourlyRate(req.hourlyRate());
        r.setCurrency(req.currency());
        applyImages(r, req.imageMediaIds());
        Room saved = repo.save(r);
        audit.record(AuditService.Actions.UPDATE, "ROOM", saved.getId(), before, auditableFields(saved));
        return saved;
    }

    private static Map<String, Object> auditableFields(Room r) {
        return AuditService.snapshot(
                "name", r.getName(), "roomNumber", r.getRoomNumber(), "building", r.getBuilding(),
                "capacity", r.getCapacity(), "status", r.getStatus().name(),
                "hourlyRate", r.getHourlyRate(),   // compared by value; see AuditService.snapshot
                "currency", r.getCurrency());
    }

    /**
     * Replace the room's image set from an ordered list of media ids. The first
     * id becomes the cover. Persisted via cascade on {@link Room#getImages()}.
     */
    private void applyImages(Room r, List<UUID> mediaIds) {
        if (mediaIds == null) return;   // not provided — leave images untouched
        r.getImages().clear();
        for (int i = 0; i < mediaIds.size(); i++) {
            UUID mediaId = mediaIds.get(i);
            MediaFile m = media.findById(mediaId)
                    .orElseThrow(() -> Errors.badRequest("INVALID_MEDIA", "Unknown media: " + mediaId));
            RoomImage img = RoomImage.builder()
                    .room(r)
                    .media(m)
                    .sortOrder(i)
                    .cover(i == 0)
                    .build();
            img.setId(UUID.randomUUID());
            r.getImages().add(img);
        }
    }

    /**
     * Deletes a room nobody has ever booked. A room with bookings on record — in any status,
     * cancelled and past ones included, since every Booking requests tab lists them — is refused
     * with 409 ROOM_HAS_BOOKINGS: retiring it takes it out of the catalogue and keeps the
     * history readable. The foreign key's ON DELETE RESTRICT never fires on a soft delete, so it
     * could not stop this by itself.
     */
    @Transactional
    public void delete(UUID id) {
        Room room = get(id);
        if (bookings.existsByRoomId(id)) {
            throw Errors.conflict("ROOM_HAS_BOOKINGS",
                    "This room has bookings on record. Set its status to RETIRED instead of deleting it.");
        }
        repo.delete(room);
        audit.record(AuditService.Actions.DELETE, "ROOM", id, null,
                AuditService.snapshot("name", room.getName(), "roomNumber", room.getRoomNumber()));
    }

    /** Friendly pre-check mirroring the DB partial unique index {@code uq_rooms_building_number}. */
    private void requireUniqueLocation(String building, String roomNumber, UUID excludeId) {
        if (repo.existsActiveByBuildingAndRoomNumber(building, roomNumber, excludeId)) {
            String where = building == null ? "room " + roomNumber
                    : "building " + building + ", room " + roomNumber;
            throw Errors.conflict("ROOM_NUMBER_TAKEN",
                    "A room already exists at " + where);
        }
    }
}
