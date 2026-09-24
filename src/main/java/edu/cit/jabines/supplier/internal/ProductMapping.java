package edu.cit.jabines.supplier.internal;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Product mapping between our product IDs and LegacySupply supplier SKUs.
 * 
 * This is the translation layer that converts our product model to supplier model.
 */
@Component
@Getter
public class ProductMapping {

    private final Map<String, SupplierProductInfo> mappings = new HashMap<>();

    public ProductMapping() {
        // Initialize product mappings
        // These are discovered during contract discovery with LegacySupply

        // P100: Wireless Mouse → Supplier SKU MS-001
        mappings.put("P100", new SupplierProductInfo(
                "MS-001",      // supplierSku
                "Wireless Mouse",
                10,            // packSize (units per pack)
                "UNITS"        // uom
        ));

        // P200: Mechanical Keyboard → Supplier SKU KB-002
        mappings.put("P200", new SupplierProductInfo(
                "KB-002",
                "Mechanical Keyboard",
                5,
                "UNITS"
        ));

        // P300: USB-C Hub → Supplier SKU HUB-003
        mappings.put("P300", new SupplierProductInfo(
                "HUB-003",
                "USB-C Hub",
                20,
                "UNITS"
        ));
    }

    /**
     * Get supplier info for our product
     */
    public SupplierProductInfo getSupplierInfo(String ourProductId) {
        SupplierProductInfo info = mappings.get(ourProductId);
        if (info == null) {
            throw new IllegalArgumentException("No supplier mapping found for product: " + ourProductId);
        }
        return info;
    }

    /**
     * Calculate cases needed based on units and pack size
     */
    public int calculateCasesNeeded(int unitsNeeded, int packSize) {
        return (int) Math.ceil((double) unitsNeeded / packSize);
    }

    /**
     * Information about a product from the supplier's perspective
     */
    public static class SupplierProductInfo {
        private final String supplierSku;
        private final String description;
        private final int packSize;     // How many units per pack/case
        private final String uom;       // Unit of measure (UNITS, CASES, etc)

        public SupplierProductInfo(String supplierSku, String description, int packSize, String uom) {
            this.supplierSku = supplierSku;
            this.description = description;
            this.packSize = packSize;
            this.uom = uom;
        }

        public String getSupplierSku() {
            return supplierSku;
        }

        public String getDescription() {
            return description;
        }

        public int getPackSize() {
            return packSize;
        }

        public String getUom() {
            return uom;
        }
    }

}
