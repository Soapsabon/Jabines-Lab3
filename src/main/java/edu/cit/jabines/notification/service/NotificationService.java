package edu.cit.jabines.notification.service;

import edu.cit.jabines.shared.event.SupplierOrderDeliveredEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Notification service that listens to domain events and sends notifications.
 * Lab 2 notification behavior: event listener pattern
 */
@Slf4j
@Service
public class NotificationService {

    @EventListener
    public void onSupplierOrderDelivered(SupplierOrderDeliveredEvent event) {
        log.info("Notification: Supplier order delivered - productId={}, units={}, orderId={}", 
            event.getProductId(), event.getUnits(), event.getSupplierOrderId());
        
        // In a real system, this would send email/SMS/push notification
        // For now, just log it
    }

}
