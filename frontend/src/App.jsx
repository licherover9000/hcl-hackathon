import React, { useState, useEffect } from 'react';
import { getApiHealth } from './services/api';

function App() {
  const [apiStatus, setApiStatus] = useState('Checking...');

  useEffect(() => {
    getApiHealth()
      .then((data) => setApiStatus(data.status || 'Connected'))
      .catch(() => setApiStatus('Backend Offline (Start server to connect)'));
  }, []);

  return (
    <div className="app-container">
      <header className="header">
        <div className="badge">HCL Hackathon Submission</div>
        <h1>Project Title</h1>
        <p className="subtitle">
          An innovative, scalable solution addressing industry challenges.
        </p>
      </header>

      <main className="card-grid">
        <div className="card">
          <h3>⚡ Core Module 1</h3>
          <p>Description of feature, business impact, and functionality.</p>
        </div>

        <div className="card">
          <h3>📊 Real-Time Analytics</h3>
          <p>Live data insights, charts, and smart performance tracking.</p>
        </div>

        <div className="card">
          <h3>🔌 API Health Status</h3>
          <div className="status-indicator">
            <span className={`status-dot ${apiStatus === 'UP' ? 'online' : 'offline'}`}></span>
            <span>{apiStatus}</span>
          </div>
        </div>
      </main>
    </div>
  );
}

export default App;
