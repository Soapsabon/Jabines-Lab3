package edu.cit.jabines.shop.repository;

import edu.cit.jabines.shop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findById(Long id);
    List<Order> findByProductId(String productId);
    List<Order> findByStatus(String status);
}
