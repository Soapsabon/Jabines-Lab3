package edu.cit.jabines.supplier.gateway;

import edu.cit.jabines.supplier.internal.LegacySupplyAdapter;
import edu.cit.jabines.supplier.model.SupplierOrder;
import edu.cit.jabines.supplier.model.SupplierOrderStatus;
import edu.cit.jabines.supplier.repository.SupplierOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the public SupplierGateway interface.
 * 
 * This is the boundary of the supplier module's anti-corruption layer.
 * All LegacySupply implementation details are hidden from other modules.
 */
@Slf4j
@Service
public class SupplierGatewayImpl implements SupplierGateway {

    private final LegacySupplyAdapter adapter;
    private final SupplierOrderRepository repository;

    @Autowired
    public SupplierGatewayImpl(LegacySupplyAdapter adapter, SupplierOrderRepository repository) {
        this.adapter = adapter;
        this.repository = repository;
    }

    @Override
    @Transactional
    public SupplierResult placeOrder(String productId, int unitsNeeded) {
        try {
            log.info("SupplierGateway.placeOrder: productId={}, units={}", productId, unitsNeeded);

            if (productId == null || productId.isEmpty()) {
                return new SupplierResult("Product ID is required");
            }

            if (unitsNeeded <= 0) {
                return new SupplierResult("Units needed must be positive");
            }

            // Delegate to adapter
            SupplierOrder order = adapter.placeOrder(productId, unitsNeeded);

            // Return success result
            return new SupplierResult(order.getId(), order.getPoNumber());

        } catch (Exception e) {
            log.error("Failed to place supplier order", e);
            return new SupplierResult("Order placement failed: " + e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierOrderStatusResult checkStatus(Long supplierOrderId) {
        try {
            log.info("SupplierGateway.checkStatus: supplierOrderId={}", supplierOrderId);

            SupplierOrder order = repository.findById(supplierOrderId)
                    .orElseThrow(() -> new IllegalArgumentException("Supplier order not found: " + supplierOrderId));

            if (order.getPoNumber() == null) {
                return new SupplierOrderStatusResult(
                        order.getStatus().toString(),
                        "Order not yet submitted to supplier"
                );
            }

            // Check with LegacySupply
            SupplierOrderStatus status = adapter.checkOrderStatus(order.getPoNumber());

            // Update order status if changed
            if (status != order.getStatus()) {
                order.setStatus(status);
                repository.save(order);
                log.info("Order status updated: id={}, newStatus={}", supplierOrderId, status);
            }

            return new SupplierOrderStatusResult(
                    status.toString(),
                    "PO#" + order.getPoNumber()
            );

        } catch (Exception e) {
            log.error("Failed to check supplier order status", e);
            return new SupplierOrderStatusResult(
                    "ERROR",
                    "Status check failed: " + e.getMessage()
            );
        }
    }

}
