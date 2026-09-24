# Jabines Lab 3: LegacySupply Integration

## Overview

This is a modular monolith Spring Boot application that integrates with the LegacySupply external supplier system. Lab 3 implements an anti-corruption layer to handle the complexity of XML-based API communication, session management, and order resilience.

## Architecture

### Modules

- **Shop Module** (`edu.cit.jabines.shop`): Manages customer orders
- **Inventory Module** (`edu.cit.jabines.inventory`): Manages product stock and low-stock detection
- **Notification Module** (`edu.cit.jabines.notification`): Listens to domain events
- **Supplier Module** (`edu.cit.jabines.supplier`): Anti-corruption layer for LegacySupply integration

### Key Design Pattern: Anti-Corruption Layer (ACL)

The supplier module is completely isolated from LegacySupply implementation details:

```
Order/Inventory → SupplierGateway (public interface)
                        ↓
                 SupplierGatewayImpl
                        ↓
                 LegacySupplyAdapter (internal)
                        ↓
              LegacySupplyClient (internal)
                        ↓
                   LegacySupply API
```

No other module knows about:
- XML request/response formats
- LegacySupply session tokens
- LegacySupply status codes
- Supplier SKUs or pack sizes
- HTTP implementation details

## Technology Stack

- **Java 17**
- **Spring Boot 3.1.5**
- **Spring Data JPA**
- **PostgreSQL** (via Supabase)
- **Maven** (build tool)
- **Jackson XML** (XML serialization)
- **Apache HttpClient 5** (HTTP requests)

## Building & Running

### Prerequisites

- Java 17+
- Maven 3.8+
- PostgreSQL (or Supabase)

### Build

```bash
mvn clean package
```

### Run

```bash
export DB_URL=jdbc:postgresql://localhost:5432/jabines_db
export DB_USER=postgres
export DB_PASSWORD=your_password
export LS_API_KEY=your_legacysupply_api_key
export LS_BASE_URL=https://legacysupply.onrender.com/api/v1
export LS_CLIENT_ID=22-4660-812

java -jar target/jabines-app-1.0.0-lab3.jar
```

The application will start on `http://localhost:8080/api`

### Database Setup

```bash
psql -U postgres -d jabines_db -f database/schema.sql
```

## API Endpoints

### Inventory

- `GET /api/inventory/products/{productId}` - Get product details
- `GET /api/inventory/products` - Get all products
- `GET /api/inventory/low-stock` - Get low-stock products

### Orders

- `POST /api/orders?productId=P100&quantity=5` - Create order
- `GET /api/orders/{orderId}` - Get order details
- `GET /api/orders/product/{productId}` - Get orders for product
- `GET /api/orders/status/{status}` - Get orders by status
- `DELETE /api/orders/{orderId}` - Cancel order

## Lab 3 Features

### 1. Anti-Corruption Layer
- All LegacySupply specifics are hidden inside the supplier module
- Clean public API through `SupplierGateway` interface
- Internal implementation handles XML, sessions, retries

### 2. Session Management
- Automatic sign-in to LegacySupply
- 1-hour session lifetime
- Automatic re-authentication on 401 errors
- No manual token management

### 3. Resilience
- **Timeouts:** 3-second maximum on all HTTP requests
- **Retries:** 3 attempts with exponential backoff
- **Idempotency:** Stable identifiers (request_id, buyer_ref) prevent duplicates
- **Never-lose guarantee:** PENDING orders persisted and retried

### 4. Auto-Reorder
When inventory drops below reorder_level:
1. Detect low stock
2. Create supplier order
3. Submit to LegacySupply
4. Store with stable identifiers
5. Retry if failed
6. Track status
7. Restock when delivered

### 5. Delivery Tracking
- Scheduled job checks order status every 10 minutes
- Publishes domain event when order is delivered
- Inventory module listens and restocks automatically

### 6. Unit Translation
- Our system: Works in individual units
- Supplier: Sells in cases
- Conversion: `cases = ceil(units / packSize)`
- Example: 17 units with pack size 10 = 2 cases

## Configuration

See `src/main/resources/application.properties` for all configuration options.

**Critical:** Use environment variables for secrets:
- `LS_API_KEY` - LegacySupply API key (NEVER hardcode)
- `DB_PASSWORD` - Database password

## Testing

### Manual Testing Flow

1. **Create an order** (should reserve inventory):
   ```bash
   curl -X POST "http://localhost:8080/api/orders?productId=P100&quantity=20"
   ```

2. **Check low stock** (should trigger reorder):
   ```bash
   curl "http://localhost:8080/api/inventory/low-stock"
   ```

3. **Monitor supplier order status**:
   - Check logs for submission attempts
   - Verify database: `SELECT * FROM supplier_orders;`

4. **Simulate delivery**:
   - Wait for status check job (10 min interval)
   - Or manually trigger status update
   - Verify inventory is restocked

### Database Verification

```sql
-- Check products
SELECT * FROM products;

-- Check orders
SELECT * FROM orders;

-- Check supplier orders
SELECT * FROM supplier_orders;
```

## Documentation

- **INTEGRATION.md** - Detailed LegacySupply integration documentation
- **REFLECTION.md** - Lab 3 reflection and self-check answers

## Troubleshooting

### Session Expiration Errors
- This is normal! The adapter handles it automatically
- If you see "Session expired (401)", don't worry - it will sign in again

### Timeout Errors
- LegacySupply may be slow or unreachable
- Orders are automatically retried every 5 minutes
- Check that `LS_BASE_URL` is correct

### API Key Not Working
- Verify `LS_API_KEY` environment variable is set
- Check it's the correct key for the client ID `22-4660-812`
- Confirm it hasn't expired

## Security Notes

- **API Key:** Never hardcode, always use `LS_API_KEY` environment variable
- **Session Tokens:** Kept in memory, never persisted or logged
- **Credentials:** Not exposed through REST APIs
- **Database:** Ensure Supabase PostgreSQL has proper access controls

## Project Structure

```
src/main/java/edu/cit/jabines/
  ├── Application.java
  ├── inventory/
  │   ├── model/Product.java
  │   ├── repository/ProductRepository.java
  │   ├── service/InventoryService.java
  │   └── controller/InventoryController.java
  ├── shop/
  │   ├── model/Order.java
  │   ├── repository/OrderRepository.java
  │   ├── service/OrderService.java
  │   └── controller/OrderController.java
  ├── notification/
  │   └── service/NotificationService.java
  ├── supplier/
  │   ├── gateway/
  │   │   ├── SupplierGateway.java (public interface)
  │   │   └── SupplierGatewayImpl.java
  │   ├── internal/
  │   │   ├── LegacySupplyClient.java
  │   │   ├── LegacySupplyAdapter.java
  │   │   ├── LegacySupplySession.java
  │   │   └── ProductMapping.java
  │   ├── job/SupplierScheduledTasks.java
  │   ├── model/SupplierOrder.java
  │   └── repository/SupplierOrderRepository.java
  └── shared/
      └── event/SupplierOrderDeliveredEvent.java
```

## Lab 2 Compatibility

All Lab 2 functionality is preserved:
- ✅ Order creation and cancellation
- ✅ Inventory reservation and release
- ✅ Low-stock detection
- ✅ Domain events and notifications
- ✅ REST APIs

Lab 3 extends Lab 2 by adding supplier integration, but does not modify existing behavior.

## Contact

Student: Snyd Jabines (22-4660-812)
Course: IT317 Project Management
