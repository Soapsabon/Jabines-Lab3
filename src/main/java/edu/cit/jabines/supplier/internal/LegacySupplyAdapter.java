package edu.cit.jabines.supplier.internal;

import edu.cit.jabines.supplier.model.SupplierOrder;
import edu.cit.jabines.supplier.model.SupplierOrderStatus;
import edu.cit.jabines.supplier.repository.SupplierOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class LegacySupplyAdapter {

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

    public SupplierOrder placeOrder(String productId, int unitsNeeded) {
        log.info("Adapter: placing order for product={}, units={}", productId, unitsNeeded);

        try {
            ProductMapping.SupplierProductInfo productInfo =
                    productMapping.getSupplierInfo(productId);

            List<SupplierOrderStatus> activeStatuses = List.of(
                    SupplierOrderStatus.PENDING,
                    SupplierOrderStatus.SUBMITTED,
                    SupplierOrderStatus.OPEN
            );

            List<SupplierOrder> existingOrders =
                    supplierOrderRepository.findByProductIdAndStatusIn(productId, activeStatuses);

            if (!existingOrders.isEmpty()) {
                SupplierOrder existing = existingOrders.get(0);

                if (existing.getStatus() == SupplierOrderStatus.PENDING) {
                    log.info("Retrying existing pending supplier order: id={}, buyerRef={}",
                            existing.getId(), existing.getBuyerRef());

                    submitOrderWithRetry(existing, productInfo);
                } else {
                    log.info("Existing active supplier order found: id={}, po={}, status={}",
                            existing.getId(), existing.getPoNumber(), existing.getStatus());
                }

                return existing;
            }

            int casesNeeded =
                    productMapping.calculateCasesNeeded(unitsNeeded, productInfo.getPackSize());

            String requestId = UUID.randomUUID().toString();
            String buyerRef = generateBuyerRef();

            SupplierOrder supplierOrder = SupplierOrder.builder()
                    .productId(productId)
                    .buyerRef(buyerRef)
                    .requestId(requestId)
                    .cases(casesNeeded)
                    .units(casesNeeded * productInfo.getPackSize())
                    .status(SupplierOrderStatus.PENDING)
                    .build();

            SupplierOrder savedOrder = supplierOrderRepository.save(supplierOrder);

            log.info("Supplier order created: id={}, buyerRef={}, requestId={}",
                    savedOrder.getId(), buyerRef, requestId);

            submitOrderWithRetry(savedOrder, productInfo);

            return savedOrder;

        } catch (Exception e) {
            log.error("Failed to place order", e);
            throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
        }
    }

    private void submitOrderWithRetry(
            SupplierOrder supplierOrder,
            ProductMapping.SupplierProductInfo productInfo) {

        int attempt = 0;
        IOException lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                attempt++;

                log.info("Submitting order attempt {}/{}: buyerRef={}, requestId={}",
                        attempt,
                        MAX_RETRIES,
                        supplierOrder.getBuyerRef(),
                        supplierOrder.getRequestId());

                ensureSession();

                LegacySupplyClient.PurchaseOrderResponse poResponse =
                        client.submitPurchaseOrder(
                                session.getToken(),
                                supplierOrder.getRequestId(),
                                supplierOrder.getBuyerRef(),
                                productInfo.getSupplierSku(),
                                supplierOrder.getCases(),
                                productInfo.getUom()
                        );

                supplierOrder.setPoNumber(poResponse.getPoNumber());
                supplierOrder.setStatus(SupplierOrderStatus.SUBMITTED);
                supplierOrderRepository.save(supplierOrder);

                log.info("Order submitted successfully: id={}, po={}",
                        supplierOrder.getId(),
                        poResponse.getPoNumber());

                return;

            } catch (LegacySupplyClient.SessionExpiredException e) {
                log.warn("Session expired, invalidating and retrying");
                session.invalidate();
                lastException = e;

            } catch (IOException e) {
                log.warn("Submission attempt {} failed: {}",
                        attempt,
                        e.getMessage());

                lastException = e;

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("All {} retry attempts failed for order: {}",
                MAX_RETRIES,
                supplierOrder.getId());

        supplierOrder.setStatus(SupplierOrderStatus.PENDING);
        supplierOrderRepository.save(supplierOrder);

        throw new RuntimeException(
                "Order submission failed after " + MAX_RETRIES + " attempts: " +
                        (lastException != null
                                ? lastException.getMessage()
                                : "unknown error"));
    }

    public SupplierOrderStatus checkOrderStatus(String poNumber) {
        try {
            ensureSession();

            LegacySupplyClient.StatusResponse statusResponse =
                    client.checkOrderStatus(
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

    private void ensureSession() throws IOException {
        if (!session.isValid()) {
            log.info("Session invalid or expired, signing in");
            String token = client.signIn();
            session.setToken(token);
        }
    }

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

    private String generateBuyerRef() {
        return "RO-" + UUID.randomUUID()
                .toString()
                .substring(0, 8)
                .toUpperCase();
    }
}
