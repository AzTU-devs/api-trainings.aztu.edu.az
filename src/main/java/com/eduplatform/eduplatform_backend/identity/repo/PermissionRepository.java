package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.identity.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    java.util.Optional<Permission> findByCode(String code);

    @Query("""
           select distinct p.code
           from User u
             join u.userRoles ur
             join ur.role r
             join r.permissions p
           where u.id = :userId
           """)
    Set<String> findPermissionCodesByUserId(@Param("userId") UUID userId);
}
