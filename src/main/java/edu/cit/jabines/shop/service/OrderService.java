package edu.cit.jabines.shop.service;

import edu.cit.jabines.shop.model.Order;
import edu.cit.jabines.shop.repository.OrderRepository;
import edu.cit.jabines.inventory.service.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;

    @Autowired
    public OrderService(OrderRepository orderRepository, InventoryService inventoryService) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
    }

    /**
     * Create a new order with inventory reservation
     */
    @Transactional
    public Order createOrder(String productId, int quantity) {
        log.info("Creating order: productId={}, quantity={}", productId, quantity);

        // Try to reserve inventory
        if (!inventoryService.reserveStock(productId, quantity)) {
            throw new IllegalArgumentException("Insufficient stock for product: " + productId);
        }

        // Create order
        Order order = Order.builder()
                .productId(productId)
                .quantity(quantity)
                .status("CONFIRMED")
                .build();

        Order savedOrder = orderRepository.save(order);
        log.info("Order created: id={}, status={}", savedOrder.getId(), savedOrder.getStatus());

        return savedOrder;
    }

    /**
     * Cancel an order and release inventory
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if ("CANCELLED".equals(order.getStatus())) {
            log.warn("Order already cancelled: {}", orderId);
            return;
        }

        // Release inventory
        inventoryService.releaseStock(order.getProductId(), order.getQuantity());

        // Update order status
        order.setStatus("CANCELLED");
        orderRepository.save(order);
        log.info("Order cancelled: id={}", orderId);
    }

    /**
     * Get order by ID
     */
    public Optional<Order> getOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }

    /**
     * Get all orders for a product
     */
    public List<Order> getOrdersByProduct(String productId) {
        return orderRepository.findByProductId(productId);
    }

    /**
     * Get orders by status
     */
    public List<Order> getOrdersByStatus(String status) {
        return orderRepository.findByStatus(status);
    }

}
