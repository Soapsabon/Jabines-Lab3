-- Lab 3 Database Schema for Jabines Project
-- Modular Monolith with LegacySupply Integration

-- ============================================
-- PRODUCTS TABLE (Inventory Module)
-- ============================================
CREATE TABLE IF NOT EXISTS products (
    product_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    stock INTEGER NOT NULL DEFAULT 0,
    reorder_level INTEGER NOT NULL DEFAULT 10,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- ORDERS TABLE (Shop Module)
-- ============================================
CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    product_id VARCHAR(50) NOT NULL REFERENCES products(product_id),
    quantity INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- SUPPLIER ORDERS TABLE (Supplier Module)
-- ============================================
CREATE TABLE IF NOT EXISTS supplier_orders (
    id BIGSERIAL PRIMARY KEY,
    product_id VARCHAR(50) NOT NULL REFERENCES products(product_id),
    buyer_ref VARCHAR(100) NOT NULL UNIQUE,
    request_id VARCHAR(100) NOT NULL UNIQUE,
    po_number VARCHAR(100),
    cases INTEGER NOT NULL,
    units INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- INITIAL DATA
-- ============================================

-- Insert initial products from Lab 2
DELETE FROM supplier_orders;
DELETE FROM orders;
DELETE FROM products;

INSERT INTO products (product_id, name, stock, reorder_level) VALUES
    ('P100', 'Wireless Mouse', 25, 10),
    ('P200', 'Mechanical Keyboard', 10, 8),
    ('P300', 'USB-C Hub', 0, 5);

-- ============================================
-- INDEXES
-- ============================================
CREATE INDEX IF NOT EXISTS idx_orders_product_id ON orders(product_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON orders(status);
CREATE INDEX IF NOT EXISTS idx_supplier_orders_product_id ON supplier_orders(product_id);
CREATE INDEX IF NOT EXISTS idx_supplier_orders_status ON supplier_orders(status);
CREATE INDEX IF NOT EXISTS idx_supplier_orders_po_number ON supplier_orders(po_number);
