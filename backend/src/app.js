const express = require('express');
const cors = require('cors');
require('dotenv').config();

// Builds the Express app without listening, so tests can use it without opening a port.
const app = express();

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Health Check Route
app.get('/api/health', (req, res) => {
  res.status(200).json({
    status: 'UP',
    message: 'Backend server is running smoothly',
    timestamp: new Date().toISOString()
  });
});

// Import and use routes
const apiRoutes = require('./routes/api');
app.use('/api', apiRoutes);

// Error Handling Middleware
app.use((err, req, res, next) => {
  console.error(err.stack);
  res.status(err.status || 500).json({
    success: false,
    message: err.message || 'Internal Server Error'
  });
});

module.exports = app;
