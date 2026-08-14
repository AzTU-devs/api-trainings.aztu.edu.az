package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findAllByUserId(UUID userId);

    @Modifying
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id = :roleId")
    int revoke(@Param("userId") UUID userId, @Param("roleId") UUID roleId);
}
