package edu.cit.jabines.inventory.repository;

import edu.cit.jabines.inventory.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {
    Optional<Product> findByProductId(String productId);
    List<Product> findByStockLessThan(int threshold);
}
