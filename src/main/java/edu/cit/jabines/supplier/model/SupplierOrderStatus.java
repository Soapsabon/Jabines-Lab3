package edu.cit.jabines.supplier.model;

/**
 * Our application's internal enum for supplier order status.
 * This is completely decoupled from LegacySupply's status codes.
 */
public enum SupplierOrderStatus {
    PENDING,        // Order saved but not yet submitted to LegacySupply
    SUBMITTED,      // Sent to LegacySupply, waiting for response
    OPEN,           // LegacySupply created PO, awaiting delivery
    DELIVERED,      // Delivered by supplier
    CANCELLED,      // Order was cancelled
    FAILED          // Permanent failure, will not retry
}
