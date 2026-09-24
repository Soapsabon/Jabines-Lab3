# Build Instructions for Lab 3

## Prerequisites

- **Java 17 or higher**: https://adoptopenjdk.net/
- **Maven 3.8+**: https://maven.apache.org/download.cgi
- **PostgreSQL 12+** or **Supabase**: https://www.postgresql.org/ or https://supabase.com/

## Environment Setup

### 1. Database Setup

Create a PostgreSQL database for the project:

```bash
# Using psql
createdb jabines_db

# Initialize schema
psql -U postgres -d jabines_db -f database/schema.sql
```

Or use Supabase:
```bash
# Create a project on Supabase
# Get your connection string from: Settings > Database > Connection String
# Format: postgresql://[user]:[password]@[host]:[port]/[database]
```

### 2. Environment Variables

Create a `.env` file in the project root (NOT committed to git):

```bash
# Database
export DB_URL="jdbc:postgresql://localhost:5432/jabines_db"
export DB_USER="postgres"
export DB_PASSWORD="your_password"

# LegacySupply
export LS_API_KEY="your_actual_api_key_here"
export LS_BASE_URL="https://legacysupply.onrender.com/api/v1"
export LS_CLIENT_ID="22-4660-812"

# Scheduler (optional, uses defaults if not set)
export app.scheduler.retry-interval-minutes=5
export app.scheduler.status-check-interval-minutes=10
```

Then load environment variables:

```bash
# Linux/Mac
source .env

# Windows PowerShell
# Create .env.ps1 and run: .\Set-EnvVars.ps1
```

## Building the Project

### Backend

```bash
# Clean and build
mvn clean install

# Build without running tests
mvn clean package -DskipTests

# Build with all tests
mvn clean package
```

### Frontend

```bash
cd frontend

# Install dependencies
npm install

# Build production bundle
npm run build
```

## Running the Application

### Option 1: Spring Boot Application

```bash
# Set environment variables first
source .env

# Run from JAR
java -jar target/jabines-app-1.0.0-lab3.jar

# Or run with Maven
mvn spring-boot:run
```

The application will start on: http://localhost:8080/api

### Option 2: Development Mode

```bash
# Terminal 1: Backend
source .env
mvn spring-boot:run

# Terminal 2: Frontend
cd frontend
npm start
```

Frontend will be available on: http://localhost:3000

## API Testing

### cURL Examples

```bash
# Create order
curl -X POST "http://localhost:8080/api/orders?productId=P100&quantity=20"

# Get product
curl "http://localhost:8080/api/inventory/products/P100"

# Get low-stock products
curl "http://localhost:8080/api/inventory/low-stock"

# Get orders
curl "http://localhost:8080/api/orders/status/CONFIRMED"

# Cancel order
curl -X DELETE "http://localhost:8080/api/orders/1"
```

### Testing Workflow

1. **Start the application**
2. **Create orders** that will trigger low-stock reorder
3. **Monitor logs** for supplier order submission
4. **Wait 5 minutes** for retry job to process pending orders
5. **Wait 10 minutes** for status check job to update delivery
6. **Check database** for supplier_orders updates

```sql
-- Monitor supplier orders
SELECT * FROM supplier_orders;

-- Monitor products stock
SELECT * FROM products;

-- Monitor customer orders  
SELECT * FROM orders;
```

## Database Verification

```bash
psql -U postgres -d jabines_db

# Check tables
\dt

# Check products
SELECT * FROM products;

# Check orders
SELECT * FROM orders;

# Check supplier orders
SELECT * FROM supplier_orders;
```

## Troubleshooting Build Issues

### "Maven command not found"
**Solution:** Install Maven and add to PATH, or use Maven wrapper:
```bash
./mvnw clean install
```

### "PostgreSQL connection refused"
**Solution:** Verify PostgreSQL is running:
```bash
# Linux
systemctl status postgresql

# Mac
brew services list | grep postgres

# Windows
# Check Services for PostgreSQL
```

### "LegacySupply API timeout"
**Solution:** Verify URL and network access:
```bash
# Test connectivity
curl -v https://legacysupply.onrender.com/api/v1/signin
```

### "API Key invalid"
**Solution:** Verify API key is correct:
```bash
# Check environment variable is set
echo $LS_API_KEY

# Should output your API key, not empty
```

## Build Output

After successful build:

```
✓ Backend JAR: target/jabines-app-1.0.0-lab3.jar
✓ Frontend build: frontend/build/
✓ Database initialized: supplier_orders table created
✓ Logs: logs/ directory created
```

## Docker (Optional)

If you want to containerize the application:

```dockerfile
FROM openjdk:17-slim
COPY target/jabines-app-1.0.0-lab3.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:
```bash
docker build -t jabines-lab3 .
docker run -e DB_URL=... -e LS_API_KEY=... -p 8080:8080 jabines-lab3
```

## Continuous Integration

For CI/CD pipelines (GitHub Actions, GitLab CI, etc.):

```yaml
- name: Build
  run: mvn clean install

- name: Test
  run: mvn test

- name: Package
  run: mvn package -DskipTests
```

## Production Deployment

See README.md for production recommendations including:
- Session pooling
- Circuit breaker pattern
- Monitoring and metrics
- Audit trails
- Error handling

---

**Still having issues?** Check:
1. Java version: `java -version`
2. Maven version: `mvn -version`
3. Database connection: `psql -U postgres -d jabines_db`
4. Network connectivity: `curl https://legacysupply.onrender.com/verify`
5. Logs: `tail -f logs/spring.log`
