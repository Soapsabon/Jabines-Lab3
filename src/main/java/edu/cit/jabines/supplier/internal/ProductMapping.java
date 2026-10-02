package edu.cit.jabines.supplier.internal;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates OUR product ids into LegacySupply item facts.
 *
 * The SupplierSku for each of our products is a fixed business decision (see INTEGRATION.md).
 * PackSize is NOT hardcoded: it comes from the real catalog response via
 * {@link #refreshFromCatalog(List)}. If the catalog has not been loaded, or a mapped SKU is
 * missing from it, lookups fail loudly instead of guessing.
 */
@Component
public class ProductMapping {

    /** LegacySupply's unit of measure for orders: Qty is a number of cases ("CS"). */
    public static final String ORDER_UOM = "CS";

    /** Our product id -> LegacySupply SupplierSku (taken from the real catalog). */
    private static final Map<String, String> SKU_BY_PRODUCT = Map.of(
            "P100", "YQB-8502",   // Wireless Mouse        -> WIRELESS MOUSE 2.4GHZ
            "P200", "YQB-8414",   // Mechanical Keyboard   -> KEYBOARD MECH TKL
            "P300", "YQB-9577"    // USB-C Hub             -> USB HUB 4-PORT (closest catalog item)
    );

    /** Latest catalog, keyed by SupplierSku. Replaced as a whole on every refresh. */
    private volatile Map<String, LegacySupplyClient.CatalogItem> catalogBySku = Map.of();

    /** True once a catalog has been loaded. */
    public boolean isCatalogLoaded() {
        return !catalogBySku.isEmpty();
    }

    /** Replace the known catalog with the one just read from LegacySupply. */
    public void refreshFromCatalog(List<LegacySupplyClient.CatalogItem> items) {
        Map<String, LegacySupplyClient.CatalogItem> fresh = new HashMap<>();
        if (items != null) {
            for (LegacySupplyClient.CatalogItem item : items) {
                if (item != null && item.getSupplierSku() != null && item.getPackSize() > 0) {
                    fresh.put(item.getSupplierSku(), item);
                }
            }
        }
        this.catalogBySku = Map.copyOf(fresh);
    }

    /**
     * Supplier facts for one of our products.
     *
     * @throws IllegalArgumentException if we have no mapping for this product id
     * @throws IllegalStateException    if the catalog is not loaded or lacks the mapped SKU
     */
    public SupplierProductInfo getSupplierInfo(String ourProductId) {
        String sku = SKU_BY_PRODUCT.get(ourProductId);
        if (sku == null) {
            throw new IllegalArgumentException("No supplier mapping found for product: " + ourProductId);
        }

        Map<String, LegacySupplyClient.CatalogItem> catalog = this.catalogBySku;
        if (catalog.isEmpty()) {
            throw new IllegalStateException("Supplier catalog not loaded yet; cannot price pack size for " + ourProductId);
        }

        LegacySupplyClient.CatalogItem item = catalog.get(sku);
        if (item == null) {
            throw new IllegalStateException("Mapped item " + sku + " for product " + ourProductId
                    + " is not in the supplier catalog");
        }

        return new SupplierProductInfo(sku, item.getDescription(), item.getPackSize(), ORDER_UOM);
    }

    /**
     * Cases to order for the units we need. ALWAYS rounds up: 25 units at pack size 12
     * is 3 cases (36 units), never 2.
     */
    public int calculateCasesNeeded(int unitsNeeded, int packSize) {
        if (unitsNeeded <= 0) {
            throw new IllegalArgumentException("Units needed must be positive: " + unitsNeeded);
        }
        if (packSize <= 0) {
            throw new IllegalArgumentException("Pack size must be positive: " + packSize);
        }
        return (unitsNeeded + packSize - 1) / packSize;
    }

    /** Supplier-side facts about one product. */
    public static class SupplierProductInfo {
        private final String supplierSku;
        private final String description;
        private final int packSize;     // units per case
        private final String uom;       // always "CS" for orders

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
