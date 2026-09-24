package edu.cit.jabines.supplier.job;

import edu.cit.jabines.shared.event.SupplierOrderDeliveredEvent;
import edu.cit.jabines.supplier.gateway.SupplierGateway;
import edu.cit.jabines.supplier.model.SupplierOrder;
import edu.cit.jabines.supplier.model.SupplierOrderStatus;
import edu.cit.jabines.supplier.repository.SupplierOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Scheduled tasks for supplier operations:
 * - Retry pending orders
 * - Check status of open orders
 * - Publish delivery events
 */
@Slf4j
@Component
public class SupplierScheduledTasks {

    private final SupplierOrderRepository repository;
    private final SupplierGateway gateway;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public SupplierScheduledTasks(
            SupplierOrderRepository repository,
            SupplierGateway gateway,
            ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.gateway = gateway;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Retry pending orders that haven't been submitted yet.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRateString = "${app.scheduler.retry-interval-minutes:5}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @Transactional
    public void retryPendingOrders() {
        log.debug("Running retry task for pending supplier orders");

        try {
            List<SupplierOrder> pendingOrders = repository.findByStatus(SupplierOrderStatus.PENDING);

            if (pendingOrders.isEmpty()) {
                log.debug("No pending orders to retry");
                return;
            }

            log.info("Found {} pending orders to retry", pendingOrders.size());

            for (SupplierOrder order : pendingOrders) {
                try {
                    log.info("Retrying pending order: id={}, product={}", order.getId(), order.getProductId());

                    SupplierGateway.SupplierResult result = gateway.placeOrder(
                            order.getProductId(),
                            order.getUnits()
                    );

                    if (result.isSuccess()) {
                        log.info("Pending order submitted successfully: id={}, po={}", 
                            order.getId(), result.getPoNumber());
                        // Order status is already updated by gateway
                    } else {
                        log.warn("Failed to submit pending order: id={}, error={}", 
                            order.getId(), result.getErrorMessage());
                    }

                } catch (Exception e) {
                    log.error("Error retrying pending order id={}", order.getId(), e);
                    // Continue to next order
                }
            }

        } catch (Exception e) {
            log.error("Error in retry pending orders task", e);
        }
    }

    /**
     * Check status of open/submitted orders and update them.
     * When an order is delivered, publish a delivery event.
     * Runs every 10 minutes.
     */
    @Scheduled(fixedRateString = "${app.scheduler.status-check-interval-minutes:10}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    @Transactional
    public void checkOpenOrdersStatus() {
        log.debug("Running status check task for open supplier orders");

        try {
            // Find all non-terminal orders
            List<SupplierOrder> openOrders = repository.findByStatusNotIn(
                    List.of(SupplierOrderStatus.DELIVERED, SupplierOrderStatus.CANCELLED, SupplierOrderStatus.FAILED)
            );

            if (openOrders.isEmpty()) {
                log.debug("No open orders to check");
                return;
            }

            log.info("Checking status of {} open orders", openOrders.size());

            for (SupplierOrder order : openOrders) {
                if (order.getPoNumber() == null) {
                    log.debug("Skipping order without PO number: id={}", order.getId());
                    continue;
                }

                try {
                    SupplierGateway.SupplierOrderStatusResult result = gateway.checkStatus(order.getId());

                    log.debug("Status check result: id={}, status={}", order.getId(), result.getStatus());

                    // Re-fetch to get updated status
                    SupplierOrder refreshedOrder = repository.findById(order.getId()).orElse(order);

                    if (refreshedOrder.getStatus() == SupplierOrderStatus.DELIVERED) {
                        log.info("Order delivered: id={}, product={}, units={}", 
                            refreshedOrder.getId(), refreshedOrder.getProductId(), refreshedOrder.getUnits());

                        // Publish delivery event for inventory to listen to
                        SupplierOrderDeliveredEvent event = new SupplierOrderDeliveredEvent(
                                refreshedOrder.getId().toString(),
                                refreshedOrder.getProductId(),
                                refreshedOrder.getUnits()
                        );
                        eventPublisher.publishEvent(event);
                    }

                } catch (Exception e) {
                    log.warn("Error checking status for order id={}: {}", order.getId(), e.getMessage());
                    // Continue to next order
                }
            }

        } catch (Exception e) {
            log.error("Error in status check task", e);
        }
    }

}
