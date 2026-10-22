const express = require('express');
const router = express.Router();

// Sample hackathon endpoint
router.get('/info', (req, res) => {
  res.json({
    project: 'HCL Hackathon Solution',
    status: 'Active',
    version: '1.0.0'
  });
});

module.exports = router;
