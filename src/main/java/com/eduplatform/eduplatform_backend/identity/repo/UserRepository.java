package com.eduplatform.eduplatform_backend.identity.repo;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /**
     * Admin user listing with optional filters. Any of {@code search}, {@code status},
     * {@code role} may be null to skip that predicate. {@code search} matches email,
     * first or last name (case-insensitive substring).
     */
    @Query("""
           select distinct u from User u
             left join u.userRoles ur
             left join ur.role r
           where (:search is null
                  or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.firstName) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.lastName) like lower(concat('%', cast(:search as string), '%')))
             and (:status is null or u.status = :status)
             and (:role is null or r.code = :role)
           """)
    Page<User> searchForAdmin(@Param("search") String search,
                              @Param("status") UserStatus status,
                              @Param("role") RoleCode role,
                              Pageable pageable);

    @Query("select u from User u where u.status = :status")
    java.util.List<User> findAllByStatus(@Param("status") UserStatus status);

    long countByStatus(UserStatus status);

    /** Count of distinct users holding any of the given roles — used to gate admin self-registration. */
    @Query("""
           select count(distinct u.id) from User u
             join u.userRoles ur
             join ur.role r
           where r.code in :codes
           """)
    long countByRoleCodes(@Param("codes") java.util.Collection<RoleCode> codes);

    /** Active users holding the given role — used for role-targeted notification broadcasts. */
    @Query("""
           select distinct u from User u
             join u.userRoles ur
             join ur.role r
           where r.code = :role and u.status = :status
           """)
    java.util.List<User> findAllByRoleAndStatus(@Param("role") RoleCode role, @Param("status") UserStatus status);

    @Modifying
    @Query("update User u set u.lastLoginAt = :ts, u.failedLogins = 0 where u.id = :id")
    int markLoginSuccess(@Param("id") UUID id, @Param("ts") Instant ts);

    @Modifying
    @Query("update User u set u.failedLogins = u.failedLogins + 1 where u.id = :id")
    int incrementFailedLogins(@Param("id") UUID id);
}
