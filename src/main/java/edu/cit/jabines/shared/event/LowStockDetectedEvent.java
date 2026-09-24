package edu.cit.jabines.shared.event;

public class LowStockDetectedEvent {

    private final String productId;
    private final int unitsNeeded;

    public LowStockDetectedEvent(String productId, int unitsNeeded) {
        this.productId = productId;
        this.unitsNeeded = unitsNeeded;
    }

    public String getProductId() {
        return productId;
    }

    public int getUnitsNeeded() {
        return unitsNeeded;
    }
}
