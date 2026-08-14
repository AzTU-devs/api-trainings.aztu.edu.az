package com.eduplatform.eduplatform_backend.payment.repo;

import com.eduplatform.eduplatform_backend.common.enums.OrderStatus;
import com.eduplatform.eduplatform_backend.payment.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByOrderNumber(String orderNumber);

    Page<Order> findAllByUserIdOrderByPlacedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findAllByStatus(OrderStatus status, Pageable pageable);
}
