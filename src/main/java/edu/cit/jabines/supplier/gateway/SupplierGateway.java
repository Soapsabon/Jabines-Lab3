package edu.cit.jabines.supplier.gateway;

/**
 * Public gateway interface for supplier operations.
 * 
 * This is the anti-corruption layer public API.
 * Other modules (inventory, shop) only interact with this interface.
 * 
 * All LegacySupply implementation details are hidden inside the supplier module.
 */
public interface SupplierGateway {

    /**
     * Place a purchase order for a product.
     * 
     * @param productId Our product ID (e.g., "P100")
     * @param unitsNeeded Number of units we need
     * @return Result containing our supplier order ID or error message
     * @throws SupplierGatewayException if operation fails
     */
    SupplierResult placeOrder(String productId, int unitsNeeded);

    /**
     * Check the current status of a supplier order.
     * 
     * @param supplierOrderId Our internal supplier order ID
     * @return Current status in our terminology
     */
    SupplierOrderStatusResult checkStatus(Long supplierOrderId);

    /**
     * Result class for place order operations
     */
    class SupplierResult {
        private final Long supplierOrderId;
        private final String poNumber;
        private final boolean success;
        private final String errorMessage;

        public SupplierResult(Long supplierOrderId, String poNumber) {
            this.supplierOrderId = supplierOrderId;
            this.poNumber = poNumber;
            this.success = true;
            this.errorMessage = null;
        }

        public SupplierResult(String errorMessage) {
            this.supplierOrderId = null;
            this.poNumber = null;
            this.success = false;
            this.errorMessage = errorMessage;
        }

        public Long getSupplierOrderId() {
            return supplierOrderId;
        }

        public String getPoNumber() {
            return poNumber;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Status result for checking order status
     */
    class SupplierOrderStatusResult {
        private final String status; // PENDING, SUBMITTED, OPEN, DELIVERED, CANCELLED, FAILED
        private final String details;

        public SupplierOrderStatusResult(String status, String details) {
            this.status = status;
            this.details = details;
        }

        public String getStatus() {
            return status;
        }

        public String getDetails() {
            return details;
        }
    }

    /**
     * Exception thrown by supplier gateway
     */
    class SupplierGatewayException extends RuntimeException {
        public SupplierGatewayException(String message) {
            super(message);
        }

        public SupplierGatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
