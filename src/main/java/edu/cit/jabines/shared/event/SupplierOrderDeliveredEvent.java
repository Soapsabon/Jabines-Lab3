package edu.cit.jabines.shared.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Event published when a supplier order is delivered.
 * Used to trigger inventory restocking.
 */
public class SupplierOrderDeliveredEvent {

    private final String supplierOrderId;
    private final String productId;
    private final int units;
    private final LocalDateTime deliveredAt;

    public SupplierOrderDeliveredEvent(String supplierOrderId, String productId, int units) {
        this.supplierOrderId = supplierOrderId;
        this.productId = productId;
        this.units = units;
        this.deliveredAt = LocalDateTime.now();
    }

    public String getSupplierOrderId() {
        return supplierOrderId;
    }

    public String getProductId() {
        return productId;
    }

    public int getUnits() {
        return units;
    }

    public LocalDateTime getDeliveredAt() {
        return deliveredAt;
    }

}
