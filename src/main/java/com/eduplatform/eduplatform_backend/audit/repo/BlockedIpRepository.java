package com.eduplatform.eduplatform_backend.audit.repo;

import com.eduplatform.eduplatform_backend.audit.domain.BlockedIp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BlockedIpRepository extends JpaRepository<BlockedIp, UUID> {

    Optional<BlockedIp> findByIpAddress(String ipAddress);

    boolean existsByIpAddress(String ipAddress);

    @org.springframework.data.jpa.repository.Query("select b.ipAddress from BlockedIp b")
    List<String> findAllIpStrings();
}
