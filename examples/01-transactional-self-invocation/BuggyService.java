package com.example.orders;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final AuditLogRepository auditLogRepository;

    public OrderService(OrderRepository orderRepository,
                        AuditLogRepository auditLogRepository) {
        this.orderRepository = orderRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public Order placeOrder(OrderRequest request) {
        // ... build the order, validate inventory, etc.
        Order order = new Order(request);

        // Persist inside its own transaction so that the audit log below
        // is still written even if the audit insert fails.
        persistOrder(order);

        auditLogRepository.record("order.placed", order.getId());
        return order;
    }

    @Transactional
    protected void persistOrder(Order order) {
        orderRepository.save(order);
    }
}
