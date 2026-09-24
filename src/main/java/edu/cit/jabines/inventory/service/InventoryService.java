package edu.cit.jabines.inventory.service;

import edu.cit.jabines.inventory.model.Product;
import edu.cit.jabines.inventory.repository.ProductRepository;
import edu.cit.jabines.shared.event.SupplierOrderDeliveredEvent;
import edu.cit.jabines.shared.event.LowStockDetectedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class InventoryService {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public InventoryService(ProductRepository productRepository, ApplicationEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Reserve stock for an order
     */
    @Transactional
    public boolean reserveStock(String productId, int quantity) {
        Product product = productRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (product.getStock() < quantity) {
            log.warn("Insufficient stock for product {}: available={}, requested={}", 
                productId, product.getStock(), quantity);
            return false;
        }

        product.setStock(product.getStock() - quantity);
        productRepository.save(product);
        
        checkLowStock(productId);
        return true;
    }

    /**
     * Release reserved stock (e.g., order cancellation)
     */
    @Transactional
    public void releaseStock(String productId, int quantity) {
        Product product = productRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        product.setStock(product.getStock() + quantity);
        productRepository.save(product);
    }

    /**
     * Check if product is low on stock and trigger reorder event
     */
    @Transactional
    public void checkLowStock(String productId) {
        Product product = productRepository.findByProductId(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (product.getStock() < product.getReorderLevel()) {
            log.info("Low stock detected for product {}: current={}, threshold={}", 
                productId, product.getStock(), product.getReorderLevel());
            int unitsNeeded = product.getReorderLevel() - product.getStock();
            eventPublisher.publishEvent(
                    new LowStockDetectedEvent(productId, unitsNeeded)
            );
        }
    }

    /**
     * Get product information
     */
    public Optional<Product> getProduct(String productId) {
        return productRepository.findByProductId(productId);
    }

    /**
     * Get all products with low stock
     */
    public List<Product> getLowStockProducts() {
        return productRepository.findAll().stream()
                .filter(p -> p.getStock() < p.getReorderLevel())
                .toList();
    }

    /**
     * Handle supplier order delivery - restock inventory
     */
    @Transactional
    @EventListener
    public void onSupplierOrderDelivered(SupplierOrderDeliveredEvent event) {
        log.info("Processing supplier delivery: productId={}, units={}", 
            event.getProductId(), event.getUnits());

        Product product = productRepository.findByProductId(event.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + event.getProductId()));

        int newStock = product.getStock() + event.getUnits();
        product.setStock(newStock);
        productRepository.save(product);

        log.info("Inventory restocked: product={}, added={}, new total={}", 
            event.getProductId(), event.getUnits(), newStock);
    }

}
