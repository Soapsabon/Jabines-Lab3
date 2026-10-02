package edu.cit.jabines.supplier.model;

/**
 * Our application's internal enum for supplier order status.
 * This is completely decoupled from LegacySupply's status codes.
 */
public enum SupplierOrderStatus {
    PENDING,        // Order saved but not yet submitted to LegacySupply
    PLACED,         // Supplier accepted our request and gave us a PO number
    ACCEPTED,       // Supplier confirmed the order
    PICKING,        // Supplier is preparing the order
    SHIPPED,        // Supplier has shipped the order
    SUBMITTED,      // (legacy value, to be removed once the adapter stops using it)
    OPEN,           // (legacy value, to be removed once the adapter stops using it)
    DELIVERED,      // Delivered by supplier
    CANCELLED,      // Order was cancelled
    FAILED          // Permanent failure, will not retry
}
