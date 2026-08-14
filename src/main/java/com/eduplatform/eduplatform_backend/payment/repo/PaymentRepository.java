package com.eduplatform.eduplatform_backend.payment.repo;

import com.eduplatform.eduplatform_backend.common.enums.PaymentProvider;
import com.eduplatform.eduplatform_backend.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByOrderId(UUID orderId);

    Optional<Payment> findByProviderAndProviderIntentId(PaymentProvider provider, String providerIntentId);
}
