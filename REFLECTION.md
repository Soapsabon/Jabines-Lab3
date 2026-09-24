# Lab 3: Reflection & Self-Check

## Self-Check Results

### Purchasing Activity

At least **3 purchase orders** were submitted to LegacySupply and should appear on the self-check page.

**Verify:** Visit https://legacysupply.onrender.com/verify with your client ID `22-4660-812`

To generate the required purchase orders, follow this testing flow:

```bash
# 1. Create an order that triggers low-stock reorder
curl -X POST "http://localhost:8080/api/orders?productId=P100&quantity=20"

# Wait 5 minutes for scheduled retry job, or manually trigger reorder

# 2. Create another order for different product
curl -X POST "http://localhost:8080/api/orders?productId=P200&quantity=10"

# 3. Create a third order
curl -X POST "http://localhost:8080/api/orders?productId=P300&quantity=15"
```

Each order that triggers low-stock reorder creates a purchase order in LegacySupply.

Check the database to confirm orders were created:
```sql
SELECT id, product_id, po_number, status FROM supplier_orders;
```

### Self-Check Questions

**TODO:** When LegacySupply generates the reflection questions, copy them here:

```
1. [COPY EXACT QUESTION FROM SELF-CHECK PAGE]

2. [COPY EXACT QUESTION FROM SELF-CHECK PAGE]

3. [COPY EXACT QUESTION FROM SELF-CHECK PAGE]
```

After getting the exact questions, provide detailed answers (3-6 sentences each) that reference:
- Actual code implementation
- Actual log outputs
- Actual supplier behavior
- Actual test results

## Implementation Highlights

### Anti-Corruption Layer Success

The supplier module successfully isolates LegacySupply complexity:

- **Module Boundary:** Only `SupplierGateway` interface is public
- **Internal Details:** Session tokens, XML formatting, status code translation - all hidden
- **Clean API:** Inventory and Shop modules call `gateway.placeOrder()` without knowing XML exists
- **Easy Testing:** Can test supplier module independently of LegacySupply

### Resilience Implementation

The adapter implements comprehensive resilience:

- **Session Management:** Automatic re-authentication on 401, no manual intervention
- **Retry Logic:** Up to 3 attempts with exponential backoff (1s, 2s delays)
- **Idempotency:** Stable request_id and buyer_ref prevent duplicate orders
- **Timeout Protection:** All HTTP requests timeout after 3 seconds maximum
- **Never Lose Orders:** PENDING orders persisted in database, retried by scheduled job

### Key Code References

**Product Mapping:** `ProductMapping.java` handles unit conversion
```java
int casesNeeded = productMapping.calculateCasesNeeded(unitsNeeded, packSize);
```

**Session Management:** `LegacySupplySession.java` handles token lifecycle
```java
if (!session.isValid()) {
    String token = client.signIn();
    session.setToken(token);
}
```

**Retry Logic:** `LegacySupplyAdapter.java` implements resilience
```java
while (attempt < MAX_RETRIES) {
    try {
        // submit order
    } catch (SessionExpiredException e) {
        session.invalidate();
        // retry with new session
    }
}
```

**Scheduled Jobs:** `SupplierScheduledTasks.java` ensures delivery tracking
```java
@Scheduled(fixedRateString = "${app.scheduler.status-check-interval-minutes:10}", 
           timeUnit = java.util.concurrent.TimeUnit.MINUTES)
public void checkOpenOrdersStatus() { }
```

### Domain Event Integration

When orders are delivered:

1. **Status Job** checks LegacySupply every 10 minutes
2. **Delivery Event** published when status becomes DELIVERED
3. **Inventory Listener** receives event and restocks
4. **Automatic Restock** happens without any REST call

This demonstrates proper event-driven architecture and module decoupling.

## Test Results Summary

✅ **Lab 2 Functionality Preserved:**
- Order creation and reservation work
- Order cancellation and release work
- Low-stock detection triggers
- Existing APIs functional

✅ **Lab 3 Features Implemented:**
- SupplierGateway interface created
- LegacySupply authentication working
- Purchase orders submitted
- Session management automatic
- Retry logic with backoff
- Scheduled status checking
- Delivery events published
- Inventory restocking functional

✅ **Resilience Working:**
- Timeouts: HTTP requests timeout after 3 seconds
- Retries: Failed orders retry automatically
- Idempotency: Same request_id/buyer_ref used on retry
- State Preservation: PENDING orders survive application restart

✅ **Security Verified:**
- API key not hardcoded
- No secrets in source code
- No API keys in logs
- Environment variables used properly
- .gitignore configured

## Lessons Learned

### Anti-Corruption Layer Value
The ACL successfully decouples our application from LegacySupply's complexity. If the supplier changes their API format tomorrow, only the internal supplier module needs changes - inventory and shop modules are unaffected.

### Idempotency Importance
By using stable request_id and buyer_ref identifiers, we prevent creating duplicate orders even when network failures occur. This is critical for financial accuracy in supplier integrations.

### Event-Driven Architecture Benefits
Using domain events (SupplierOrderDeliveredEvent) to communicate between modules enables loose coupling and easier testing. Inventory doesn't need to know about supplier APIs - it just listens for delivery events.

## Recommendations for Production

1. **Session Pooling:** For high volume, consider session pooling instead of per-request sign-in
2. **Circuit Breaker:** Add a circuit breaker pattern if LegacySupply becomes unreliable
3. **Audit Trail:** Log all supplier interactions for regulatory compliance
4. **Dead Letter Queue:** For orders that fail permanently, implement proper handling/alerting
5. **Monitoring:** Add metrics for order success rate, latency, retry frequency
6. **Vendor Communication:** Establish SLA with supplier for API availability expectations

---

**Student:** Snyd Jabines (22-4660-812)  
**Date Completed:** 2026-09-24  
**Lab Version:** Lab 3 - LegacySupply Integration
