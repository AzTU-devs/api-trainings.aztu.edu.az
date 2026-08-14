package com.eduplatform.eduplatform_backend.payment.service;

import com.eduplatform.eduplatform_backend.common.enums.OrderItemType;
import com.eduplatform.eduplatform_backend.common.enums.OrderStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.payment.domain.Order;
import com.eduplatform.eduplatform_backend.payment.domain.OrderItem;
import com.eduplatform.eduplatform_backend.payment.repo.OrderRepository;
import com.eduplatform.eduplatform_backend.payment.web.dto.OrderCreateRequest;
import com.eduplatform.eduplatform_backend.room.domain.RoomBooking;
import com.eduplatform.eduplatform_backend.room.repo.RoomBookingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.UUID;

@Service
public class OrderService {

    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final CourseRepository courses;
    private final RoomBookingRepository bookings;
    private final UserRepository users;

    public OrderService(OrderRepository orders, CourseRepository courses,
                        RoomBookingRepository bookings, UserRepository users) {
        this.orders = orders;
        this.courses = courses;
        this.bookings = bookings;
        this.users = users;
    }

    @Transactional
    public Order create(UUID userId, OrderCreateRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw Errors.badRequest("EMPTY_ORDER", "Order must contain at least one item");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));

        Order order = Order.builder()
                .user(user)
                .orderNumber("ORD-" + Math.abs(RNG.nextLong()))
                .status(OrderStatus.PENDING)
                .subtotal(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .currency(req.currency() == null ? "USD" : req.currency())
                .build();
        order.setId(UUID.randomUUID());

        BigDecimal subtotal = BigDecimal.ZERO;
        for (OrderCreateRequest.Line line : req.items()) {
            OrderItem item = toItem(order, line, order.getCurrency());
            order.getItems().add(item);
            subtotal = subtotal.add(item.getTotalPrice());
        }
        order.setSubtotal(subtotal);
        order.setTotal(subtotal); // no tax/discount yet
        return orders.save(order);
    }

    @Transactional(readOnly = true)
    public Page<Order> mine(UUID userId, Pageable pageable) {
        return orders.findAllByUserIdOrderByPlacedAtDesc(userId, pageable);
    }

    private OrderItem toItem(Order order, OrderCreateRequest.Line line, String orderCurrency) {
        if (line.itemType() == OrderItemType.COURSE) {
            if (line.courseId() == null) throw Errors.badRequest("MISSING_COURSE_ID", "COURSE item requires courseId");
            Course c = courses.findById(line.courseId())
                    .orElseThrow(() -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
            if (c.isFree()) throw Errors.badRequest("COURSE_IS_FREE", "Free courses do not require an order");
            if (!c.getCurrency().equals(orderCurrency)) {
                throw Errors.badRequest("CURRENCY_MISMATCH",
                        "Item currency '" + c.getCurrency() + "' does not match order currency '" + orderCurrency + "'");
            }
            OrderItem item = OrderItem.builder()
                    .order(order).itemType(OrderItemType.COURSE).course(c)
                    .description("Course: " + c.getTitle())
                    .quantity(1)
                    .unitPrice(c.getPrice()).totalPrice(c.getPrice())
                    .currency(c.getCurrency())
                    .build();
            item.setId(UUID.randomUUID());
            return item;
        }
        // ROOM_USAGE_FEE
        if (line.roomBookingId() == null) {
            throw Errors.badRequest("MISSING_BOOKING_ID", "ROOM_USAGE_FEE item requires roomBookingId");
        }
        RoomBooking b = bookings.findById(line.roomBookingId())
                .orElseThrow(() -> Errors.notFound("BOOKING_NOT_FOUND", "Booking does not exist"));
        if (!b.getCurrency().equals(orderCurrency)) {
            throw Errors.badRequest("CURRENCY_MISMATCH",
                    "Booking currency '" + b.getCurrency() + "' does not match order currency '" + orderCurrency + "'");
        }
        OrderItem item = OrderItem.builder()
                .order(order).itemType(OrderItemType.ROOM_USAGE_FEE).roomBooking(b)
                .description("Room booking " + b.getId())
                .quantity(1)
                .unitPrice(b.getTotalFee()).totalPrice(b.getTotalFee())
                .currency(b.getCurrency())
                .build();
        item.setId(UUID.randomUUID());
        return item;
    }
}
