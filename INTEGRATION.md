# Lab 3: LegacySupply Integration Documentation

## 1. PRODUCT MAPPING

Product mappings between our application and LegacySupply supplier:

| Our Product ID | Product Name | LegacySupply SupplierSku | LegacySupply PackSize (units) | Unit of Measure |
|---|---|---|---|---|
| P100 | Wireless Mouse | MS-001 | 10 | UNITS |
| P200 | Mechanical Keyboard | KB-002 | 5 | UNITS |
| P300 | USB-C Hub | HUB-003 | 20 | UNITS |

**Translation Note:** Our inventory module works entirely in units. When communicating with LegacySupply, we convert units to cases based on the pack size. For example, if we need 17 units of a product with pack size 10, we calculate `ceil(17 / 10) = 2` cases to order from the supplier.

## 2. SESSION MANAGEMENT

### Sign-In Process
1. **Initiation:** Application calls LegacySupply `/signin` endpoint with:
   - Client ID: `22-4660-812`
   - API Key: Retrieved from environment variable `LS_API_KEY`
   
2. **Request Format:** XML with client credentials
   ```xml
   <SignInRequest>
     <clientId>22-4660-812</clientId>
     <apiKey>${LS_API_KEY}</apiKey>
   </SignInRequest>
   ```

3. **Response:** Server returns session token
   ```xml
   <SignInResponse>
     <sessionToken>token_value_here</sessionToken>
   </SignInResponse>
   ```

### Session Storage & Transmission
- **Storage:** Session token is kept in memory in the `LegacySupplySession` component
- **Transmission:** Token is sent with each request via HTTP header: `Authorization: Bearer {token}`
- **Session Lifetime:** 3600 seconds (1 hour)

### Session Expiration & Handling
- Session is automatically invalidated after 1 hour
- If a request receives a 401 (Unauthorized) response, the session is considered expired
- On session expiration, the adapter automatically signs in again without requiring manual intervention
- This ensures seamless operation and no manual token management is needed

### When Session is Rejected
When LegacySupply returns a 401 Unauthorized:
1. Current session token is invalidated
2. The operation triggering the 401 is retried with a new session
3. A new sign-in occurs transparently
4. Original operation is attempted again with new credentials
5. If still failing after retry, order is marked FAILED

## 3. ERROR CODES

Error codes encountered during LegacySupply integration:

| HTTP Status | Error Code | Cause | Observed Behavior | Application Handling |
|---|---|---|---|---|
| 200 | (Success) | Valid purchase order | PO number returned | Order marked SUBMITTED |
| 201 | (Created) | Purchase order created | PO number returned | Order marked SUBMITTED |
| 400 | BadRequest | Invalid XML format or missing required fields | Error message returned | Order marked FAILED, not retried |
| 401 | Unauthorized | Session token expired or invalid | No response body | Session invalidated, retry with new session |
| 500 | ServerError | LegacySupply service error | Error message returned | Retried up to 3 times with exponential backoff |
| (Connection timeout) | TimeoutError | Network latency or LegacySupply unreachable | Request fails after 3 seconds | Retried up to 3 times with exponential backoff |

### Unknown Status Handling
If LegacySupply returns a status code not in this table:
- Request is **not** automatically retried
- Order is marked with status **FAILED**
- Error is logged with the unknown status code
- Application does **not** assume success and does **not** restock inventory
- Manual intervention may be required to investigate

## 4. QUANTITY AND UNIT OF MEASURE

### Understanding Qty and UOM

**Our System (Units):** Our inventory and orders work in individual units.
- Example: "We need 17 units of Wireless Mouse"

**LegacySupply (Cases):** The supplier may sell products in cases/packs, not individual units.
- Example: "Wireless Mouse is sold in cases of 10 units each"

### Conversion Calculation

When we need units from the supplier, we must round up to the nearest case:

```
Formula: CasesNeeded = ceil(UnitsNeeded / PackSize)

Example:
  Our system needs: 17 units of Wireless Mouse
  LegacySupply pack size: 10 units per case
  Calculation: ceil(17 / 10) = ceil(1.7) = 2 cases
  Supplier quantity: 2 cases (= 20 units)
```

### Actual LegacySupply Configuration

| Product | Our Units | Pack Size | Qty Sent to Supplier | Result |
|---|---|---|---|---|
| P100 (Mouse) | 1 | 10 | 1 case | 10 units delivered |
| P100 (Mouse) | 15 | 10 | 2 cases | 20 units delivered |
| P100 (Mouse) | 20 | 10 | 2 cases | 20 units delivered |
| P200 (Keyboard) | 3 | 5 | 1 case | 5 units delivered |

### API Communication

XML request to LegacySupply:
```xml
<PurchaseOrderRequest>
  <requestId>uuid-string</requestId>
  <buyerRef>RO-XXXXXXXX</buyerRef>
  <supplierSku>MS-001</supplierSku>
  <quantity>2</quantity>           <!-- This is in cases -->
  <uom>UNITS</uom>
</PurchaseOrderRequest>
```

In this context:
- `quantity` = number of cases (after conversion from our units)
- `uom` = the unit of measure for the quantity field

## 5. UNKNOWN STATUS HANDLING

### Application Behavior on Unknown LegacySupply Status

If LegacySupply returns a status that is not recognized by our application:

1. **No Crash:** The application logs the unknown status and continues
2. **No Assumption:** We do NOT assume the order was delivered
3. **No Restock:** Inventory is NOT automatically restocked
4. **Preserved State:** The supplier order is preserved in the database with status FAILED
5. **Safe Default:** We treat unknown statuses as failures (conservative approach)
6. **Logging:** The exact unknown status is logged for investigation

### Known Status Mappings

LegacySupply Status → Our Internal Status:
- `SUBMITTED`, `PENDING` → `SUBMITTED`
- `OPEN`, `PROCESSING` → `OPEN`
- `DELIVERED`, `COMPLETED` → `DELIVERED`
- `CANCELLED` → `CANCELLED`
- **(unknown)** → `FAILED` with warning log

### Example Scenario

If LegacySupply returns status `"AWAITING_PAYMENT"` (which is not in our mapping):
```
Log: WARN - Unknown LegacySupply status: AWAITING_PAYMENT
Order Status: FAILED
Inventory: NOT restocked
Action: Manual investigation required
```

## 6. AUTO-REORDER FLOW

### Low-Stock Detection

When inventory stock drops below the configured `reorder_level`:

1. **Detection:** Inventory module detects low stock after any order
2. **Evaluation:** Check if stock < reorder_level
3. **Action:** Call SupplierGateway.placeOrder()

### Reorder Process

```
Low Stock Event
    ↓
SupplierGateway.placeOrder(productId, unitsNeeded)
    ↓
Create SupplierOrder in PENDING status
    ↓
LegacySupplyAdapter.placeOrder()
    ↓
Retry Logic (up to 3 attempts)
    ├─ Ensure valid session (sign in if needed)
    ├─ Submit XML purchase order
    └─ On failure: backoff and retry
    ↓
Update SupplierOrder with PO#, mark SUBMITTED
    ↓
[Background job] Scheduled status checker
    ├─ Poll LegacySupply every 10 minutes
    ├─ When status = DELIVERED
    └─ Publish SupplierOrderDeliveredEvent
    ↓
Inventory module listens for delivery event
    ├─ Add units to inventory
    ↓
Restock complete
```

## 7. IDEMPOTENCY

### Preventing Duplicate Orders

Every reorder is protected against duplicate submission:

**Stable Identifiers:**
- `request_id`: UUID generated once, persisted, reused on retry
- `buyer_ref`: Unique reference like `RO-XXXXXXXX`, never changes
- `po_number`: Returned by LegacySupply, stored in database

**Duplicate Prevention:**
1. If network fails after LegacySupply creates PO but before we receive response:
   - Database still has the initial supplier order record
   - Retry uses same `request_id` and `buyer_ref`
   - LegacySupply recognizes duplicate submission and returns existing PO#
   - We update the order with PO# and continue

2. If application crashes:
   - PENDING orders are retried by scheduled job
   - Same `request_id` and `buyer_ref` are used
   - No duplicate POs are created

3. If database is queried:
   - We can look up orders by `request_id` or `buyer_ref`
   - Confirm if order was already submitted before

## 8. RESILIENCE CONFIGURATION

### Timeouts
- HTTP request timeout: **3 seconds maximum**
- Connection establishment: **3 seconds**
- Read timeout: **3 seconds**

### Retries
- **Maximum retry attempts:** 3
- **Backoff strategy:** Exponential
  - Attempt 1: Immediate
  - Attempt 2: Wait 1 second, retry
  - Attempt 3: Wait 2 seconds, retry
  - Fail: Mark order FAILED after 3 failures

### Retry Conditions
- **Retry on:** Connection timeouts, network errors, 500+ server errors
- **Do not retry:** 400 client errors, 401 unauthorized (handled separately)
- **Session 401:** Handled separately - sign in again and retry original operation

### Never Lose Orders
- **PENDING orders:** Persisted in database
- **Scheduled retry job:** Runs every 5 minutes
- **When LegacySupply recovers:** PENDING orders are submitted
- **Delivery tracking job:** Runs every 10 minutes, updates order statuses

## 9. REQUEST QUOTA & RATE LIMITING

LegacySupply has request quota limits. Our application respects this:

- **Status polling interval:** 10 minutes (not continuous)
- **Retry backoff:** Exponential, not rapid fire
- **No artificial test traffic:** We avoid creating orders just to test
- **Legitimate traffic only:** Orders are created for actual inventory needs

## 10. CONFIGURATION

### Environment Variables Required

```bash
# Database
DB_URL=jdbc:postgresql://localhost:5432/jabines_db
DB_USER=postgres
DB_PASSWORD=...

# LegacySupply
LS_API_KEY=your_api_key_here          # NEVER commit this
LS_BASE_URL=https://legacysupply.onrender.com/api/v1
LS_CLIENT_ID=22-4660-812

# Scheduling
app.scheduler.retry-interval-minutes=5
app.scheduler.status-check-interval-minutes=10
```

### .gitignore

```
.env
*.env
application-local.properties
logs/
target/
```

Do **NOT** commit the API key or any real credentials.
