package com.eduplatform.eduplatform_backend.room.repo;

import com.eduplatform.eduplatform_backend.room.domain.RoomPricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoomPricingRuleRepository extends JpaRepository<RoomPricingRule, UUID> {

    List<RoomPricingRule> findAllByRoomIdOrderByPriorityDesc(UUID roomId);
}
