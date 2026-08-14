package com.eduplatform.eduplatform_backend.payment.web.mapper;

import com.eduplatform.eduplatform_backend.payment.domain.Order;
import com.eduplatform.eduplatform_backend.payment.domain.OrderItem;
import com.eduplatform.eduplatform_backend.payment.domain.Payment;
import com.eduplatform.eduplatform_backend.payment.web.dto.OrderDto;
import com.eduplatform.eduplatform_backend.payment.web.dto.OrderItemDto;
import com.eduplatform.eduplatform_backend.payment.web.dto.PaymentDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "items", expression = "java(toItemDtos(order.getItems()))")
    OrderDto toOrderDto(Order order);

    @Mapping(target = "courseId",      source = "course.id")
    @Mapping(target = "roomBookingId", source = "roomBooking.id")
    OrderItemDto toItemDto(OrderItem item);

    default List<OrderItemDto> toItemDtos(java.util.Set<OrderItem> items) {
        if (items == null) return List.of();
        return items.stream().map(this::toItemDto).toList();
    }

    @Mapping(target = "orderId", source = "order.id")
    PaymentDto toPaymentDto(Payment payment);
}
