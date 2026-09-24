package edu.cit.jabines.supplier.internal;

import edu.cit.jabines.supplier.model.SupplierOrder;
import edu.cit.jabines.supplier.model.SupplierOrderStatus;
import edu.cit.jabines.supplier.repository.SupplierOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Internal adapter for LegacySupply integration.
 * 
 * This is the anti-corruption layer that:
 * - Handles authentication and session management
 * - Translates between our domain model and LegacySupply's XML/API model
 * - Implements resilience (retries, timeouts, idempotency)
 * - Persists supplier orders with stable identifiers
 * 
 * This class is internal to the supplier module and not exposed outside.
 */
@Slf4j
@Component
class LegacySupplyAdapter {

    private final LegacySupplyClient client;
    private final LegacySupplySession session;
    private final ProductMapping productMapping;
    private final SupplierOrderRepository supplierOrderRepository;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_BACKOFF_MS = 1000;

    @Autowired
    public LegacySupplyAdapter(
            LegacySupplyClient client,
            LegacySupplySession session,
            ProductMapping productMapping,
            SupplierOrderRepository supplierOrderRepository) {
        this.client = client;
        this.session = session;
        this.productMapping = productMapping;
        this.supplierOrderRepository = supplierOrderRepository;
    }

    /**
     * Place an order with retry logic and idempotency handling
     */
    public SupplierOrder placeOrder(String productId, int unitsNeeded) {
        log.info("Adapter: placing order for product={}, units={}", productId, unitsNeeded);

        try {
            // Get product mapping
            ProductMapping.SupplierProductInfo productInfo = productMapping.getSupplierInfo(productId);
            
            // Calculate cases needed
            int casesNeeded = productMapping.calculateCasesNeeded(unitsNeeded, productInfo.getPackSize());
            
            // Generate stable identifiers
            String requestId = UUID.randomUUID().toString();
            String buyerRef = generateBuyerRef();

            // Create supplier order record in PENDING status
            SupplierOrder supplierOrder = SupplierOrder.builder()
                    .productId(productId)
                    .buyerRef(buyerRef)
                    .requestId(requestId)
                    .cases(casesNeeded)
                    .units(unitsNeeded)
                    .status(SupplierOrderStatus.PENDING)
                    .build();

            SupplierOrder savedOrder = supplierOrderRepository.save(supplierOrder);
            log.info("Supplier order created in PENDING status: id={}, buyerRef={}, requestId={}", 
                savedOrder.getId(), buyerRef, requestId);

            // Try to submit to LegacySupply with retries
            submitOrderWithRetry(savedOrder, productInfo);

            return savedOrder;

        } catch (Exception e) {
            log.error("Failed to place order", e);
            throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
        }
    }

    /**
     * Submit order to LegacySupply with retry logic
     */
    private void submitOrderWithRetry(SupplierOrder supplierOrder, ProductMapping.SupplierProductInfo productInfo) {
        int attempt = 0;
        IOException lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                attempt++;
                log.info("Submitting order attempt {}/{}: buyerRef={}", 
                    attempt, MAX_RETRIES, supplierOrder.getBuyerRef());

                // Ensure valid session
                ensureSession();

                // Submit purchase order
                LegacySupplyClient.PurchaseOrderResponse poResponse = client.submitPurchaseOrder(
                        session.getToken(),
                        supplierOrder.getRequestId(),
                        supplierOrder.getBuyerRef(),
                        productInfo.getSupplierSku(),
                        supplierOrder.getCases(),
                        productInfo.getUom()
                );

                // Update supplier order with PO number and SUBMITTED status
                supplierOrder.setPoNumber(poResponse.getPoNumber());
                supplierOrder.setStatus(SupplierOrderStatus.SUBMITTED);
                supplierOrderRepository.save(supplierOrder);

                log.info("Order submitted successfully: id={}, po={}", 
                    supplierOrder.getId(), poResponse.getPoNumber());

                return; // Success

            } catch (LegacySupplyClient.SessionExpiredException e) {
                log.warn("Session expired, invalidating and retrying");
                session.invalidate();
                lastException = e;
                // Continue to retry
            } catch (IOException e) {
                log.warn("Submission attempt {} failed: {}", attempt, e.getMessage());
                lastException = e;
                // Continue to retry with backoff
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted
        log.error("All {} retry attempts failed for order: {}", MAX_RETRIES, supplierOrder.getId());
        supplierOrder.setStatus(SupplierOrderStatus.FAILED);
        supplierOrderRepository.save(supplierOrder);

        throw new RuntimeException("Order submission failed after " + MAX_RETRIES + " attempts: " + 
            (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    /**
     * Check order status with LegacySupply
     */
    public SupplierOrderStatus checkOrderStatus(String poNumber) {
        try {
            ensureSession();

            LegacySupplyClient.StatusResponse statusResponse = client.checkOrderStatus(
                    session.getToken(),
                    poNumber
            );

            return translateLegacySupplyStatus(statusResponse.getStatus());

        } catch (LegacySupplyClient.SessionExpiredException e) {
            session.invalidate();
            log.warn("Session expired while checking status, will retry");
            throw new RuntimeException("Session expired, retry later", e);
        } catch (IOException e) {
            log.error("Failed to check status for PO: {}", poNumber, e);
            throw new RuntimeException("Status check failed: " + e.getMessage(), e);
        }
    }

    /**
     * Ensure a valid session exists, sign in if needed
     */
    private void ensureSession() throws IOException {
        if (!session.isValid()) {
            log.debug("Session invalid or expired, signing in");
            String token = client.signIn();
            session.setToken(token);
        }
    }

    /**
     * Translate LegacySupply status codes to our internal enum
     */
    private SupplierOrderStatus translateLegacySupplyStatus(String legacySupplyStatus) {
        if (legacySupplyStatus == null) {
            log.warn("Received null status from LegacySupply");
            return SupplierOrderStatus.FAILED;
        }

        return switch (legacySupplyStatus.toUpperCase()) {
            case "SUBMITTED", "PENDING" -> SupplierOrderStatus.SUBMITTED;
            case "OPEN", "PROCESSING" -> SupplierOrderStatus.OPEN;
            case "DELIVERED", "COMPLETED" -> SupplierOrderStatus.DELIVERED;
            case "CANCELLED" -> SupplierOrderStatus.CANCELLED;
            default -> {
                log.warn("Unknown LegacySupply status: {}", legacySupplyStatus);
                yield SupplierOrderStatus.FAILED;
            }
        };
    }

    /**
     * Generate unique buyer reference
     */
    private String generateBuyerRef() {
        return "RO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}
