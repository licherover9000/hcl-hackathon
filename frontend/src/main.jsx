import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './styles.css';
import { getSession } from './api';
import Layout from './Layout';
import Login from './pages/Login';
import Worklist from './pages/Worklist';
import Account from './pages/Account';
import Settlements from './pages/Settlements';
import DayEnd from './pages/DayEnd';
import Dashboard from './pages/Dashboard';
import Config from './pages/Config';
import Audit from './pages/Audit';

// Hiding a page is only convenience: every API call is checked again on the server.
function Protected({ roles, children }) {
  const s = getSession();
  if (!s) return <Navigate to="/login" replace />;
  if (roles && !roles.includes(s.user.role)) return <Navigate to="/" replace />;
  return <Layout>{children}</Layout>;
}

function Home() {
  const s = getSession();
  if (!s) return <Navigate to="/login" replace />;
  const home = { COLLECTOR: '/worklist', MANAGER: '/settlements', HEAD: '/dashboard', AUDITOR: '/audit' };
  return <Navigate to={home[s.user.role] || '/worklist'} replace />;
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<Home />} />
        <Route path="/worklist" element={<Protected><Worklist /></Protected>} />
        <Route path="/loans/:loanId" element={<Protected><Account /></Protected>} />
        <Route path="/settlements" element={<Protected><Settlements /></Protected>} />
        <Route path="/day-end" element={<Protected roles={['MANAGER', 'HEAD', 'AUDITOR']}><DayEnd /></Protected>} />
        <Route path="/dashboard" element={<Protected roles={['MANAGER', 'HEAD', 'AUDITOR']}><Dashboard /></Protected>} />
        <Route path="/config" element={<Protected><Config /></Protected>} />
        <Route path="/audit" element={<Protected roles={['MANAGER', 'HEAD', 'AUDITOR']}><Audit /></Protected>} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>
);
