package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
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

    /**
     * Role codes the user holds right now. Rooted at User so its soft-delete restriction
     * applies: a deleted account resolves to no roles even if its links remain.
     */
    @Query("""
           select r.code from User u
             join u.userRoles ur
             join ur.role r
           where u.id = :userId
           """)
    List<RoleCode> findRoleCodesByUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from UserRole ur where ur.user.id = :userId and ur.role.id = :roleId")
    int revoke(@Param("userId") UUID userId, @Param("roleId") UUID roleId);
}
