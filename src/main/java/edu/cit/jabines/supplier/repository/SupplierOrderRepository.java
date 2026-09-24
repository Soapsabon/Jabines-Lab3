package edu.cit.jabines.supplier.repository;

import edu.cit.jabines.supplier.model.SupplierOrder;
import edu.cit.jabines.supplier.model.SupplierOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {
    Optional<SupplierOrder> findByBuyerRef(String buyerRef);
    Optional<SupplierOrder> findByRequestId(String requestId);
    Optional<SupplierOrder> findByPoNumber(String poNumber);
    List<SupplierOrder> findByStatus(SupplierOrderStatus status);
    List<SupplierOrder> findByStatusNotIn(List<SupplierOrderStatus> statuses);
}
