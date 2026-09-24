import React, { useState, useEffect } from 'react';
import axios from 'axios';
import './App.css';

const API_BASE = 'http://localhost:8080/api';

function App() {
  const [products, setProducts] = useState([]);
  const [orders, setOrders] = useState([]);
  const [supplierOrders, setSupplierOrders] = useState([]);
  const [activeTab, setActiveTab] = useState('products');
  const [productId, setProductId] = useState('P100');
  const [quantity, setQuantity] = useState(1);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');

  const API_BASE_INTERNAL = process.env.REACT_APP_API_BASE || 'http://localhost:8080/api';

  useEffect(() => {
    loadData();
    const interval = setInterval(loadData, 5000); // Refresh every 5 seconds
    return () => clearInterval(interval);
  }, []);

  const loadData = async () => {
    try {
      // Load products
      const productsRes = await axios.get(`${API_BASE_INTERNAL}/inventory/products`);
      setProducts(productsRes.data);

      // Load orders
      const ordersRes = await axios.get(`${API_BASE_INTERNAL}/orders/status/CONFIRMED`);
      setOrders(ordersRes.data);
    } catch (error) {
      console.error('Error loading data:', error);
    }
  };

  const createOrder = async () => {
    try {
      setLoading(true);
      setMessage('');
      
      const response = await axios.post(`${API_BASE_INTERNAL}/orders`, null, {
        params: {
          productId: productId,
          quantity: parseInt(quantity)
        }
      });

      setMessage(`✅ Order created successfully! Order ID: ${response.data.id}`);
      setQuantity(1);
      setTimeout(() => loadData(), 1000);
    } catch (error) {
      setMessage(`❌ Error: ${error.response?.data?.message || error.message}`);
    } finally {
      setLoading(false);
    }
  };

  const cancelOrder = async (orderId) => {
    try {
      setLoading(true);
      await axios.delete(`${API_BASE_INTERNAL}/orders/${orderId}`);
      setMessage(`✅ Order cancelled successfully!`);
      setTimeout(() => loadData(), 1000);
    } catch (error) {
      setMessage(`❌ Error cancelling order: ${error.message}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="app">
      <header className="header">
        <h1>Jabines Lab 3 - LegacySupply Integration</h1>
        <p>Modular Monolith with Anti-Corruption Layer</p>
      </header>

      <div className="tabs">
        <button 
          className={`tab ${activeTab === 'products' ? 'active' : ''}`}
          onClick={() => setActiveTab('products')}
        >
          📦 Inventory
        </button>
        <button 
          className={`tab ${activeTab === 'orders' ? 'active' : ''}`}
          onClick={() => setActiveTab('orders')}
        >
          📋 Orders
        </button>
        <button 
          className={`tab ${activeTab === 'create' ? 'active' : ''}`}
          onClick={() => setActiveTab('create')}
        >
          ➕ New Order
        </button>
      </div>

      {message && (
        <div className={`message ${message.includes('✅') ? 'success' : 'error'}`}>
          {message}
        </div>
      )}

      <div className="content">
        {activeTab === 'products' && (
          <div>
            <h2>Inventory Status</h2>
            <div className="product-grid">
              {products.map(product => (
                <div key={product.productId} className="product-card">
                  <h3>{product.name}</h3>
                  <p className="product-id">ID: {product.productId}</p>
                  <div className="stock">
                    <span className="label">Stock:</span>
                    <span className={`value ${product.stock < product.reorderLevel ? 'low' : 'ok'}`}>
                      {product.stock} units
                    </span>
                  </div>
                  <div className="reorder">
                    <span className="label">Reorder Level:</span>
                    <span className="value">{product.reorderLevel}</span>
                  </div>
                  {product.stock < product.reorderLevel && (
                    <div className="alert">⚠️ Low stock - reorder pending</div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {activeTab === 'orders' && (
          <div>
            <h2>Recent Orders</h2>
            {orders.length === 0 ? (
              <p className="empty">No orders yet. Create one to get started!</p>
            ) : (
              <table className="orders-table">
                <thead>
                  <tr>
                    <th>Order ID</th>
                    <th>Product</th>
                    <th>Quantity</th>
                    <th>Status</th>
                    <th>Created At</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.map(order => (
                    <tr key={order.id}>
                      <td>{order.id}</td>
                      <td>{order.productId}</td>
                      <td>{order.quantity}</td>
                      <td><span className="badge">{order.status}</span></td>
                      <td>{new Date(order.createdAt).toLocaleString()}</td>
                      <td>
                        <button 
                          className="btn-small"
                          onClick={() => cancelOrder(order.id)}
                          disabled={loading}
                        >
                          Cancel
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}

        {activeTab === 'create' && (
          <div className="create-order-form">
            <h2>Create New Order</h2>
            <div className="form-group">
              <label>Product:</label>
              <select 
                value={productId} 
                onChange={(e) => setProductId(e.target.value)}
                disabled={loading}
              >
                <option value="P100">P100 - Wireless Mouse</option>
                <option value="P200">P200 - Mechanical Keyboard</option>
                <option value="P300">P300 - USB-C Hub</option>
              </select>
            </div>

            <div className="form-group">
              <label>Quantity:</label>
              <input 
                type="number" 
                value={quantity} 
                onChange={(e) => setQuantity(e.target.value)}
                min="1"
                disabled={loading}
              />
            </div>

            <button 
              className="btn-primary"
              onClick={createOrder}
              disabled={loading}
            >
              {loading ? 'Creating...' : 'Create Order'}
            </button>

            <div className="info-box">
              <h3>How it works:</h3>
              <ol>
                <li>Select a product and enter quantity</li>
                <li>Click "Create Order" to reserve inventory</li>
                <li>If stock is low, supplier order is triggered automatically</li>
                <li>Supplier delivers goods in background</li>
                <li>Inventory is restocked automatically</li>
              </ol>
            </div>
          </div>
        )}
      </div>

      <footer className="footer">
        <p>Lab 3: LegacySupply Integration | Modular Monolith Pattern</p>
        <p>Student: Snyd Jabines (22-4660-812)</p>
      </footer>
    </div>
  );
}

export default App;
