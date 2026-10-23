import { NavLink, useNavigate } from 'react-router-dom';
import { getSession, setSession } from './api';

const NAV = [
  { to: '/worklist', label: 'Worklist', roles: ['COLLECTOR', 'MANAGER', 'HEAD', 'AUDITOR'] },
  { to: '/settlements', label: 'Settlements', roles: ['COLLECTOR', 'MANAGER', 'HEAD', 'AUDITOR'] },
  { to: '/day-end', label: 'Day-end and data', roles: ['MANAGER', 'HEAD', 'AUDITOR'] },
  { to: '/dashboard', label: 'Dashboard', roles: ['MANAGER', 'HEAD', 'AUDITOR'] },
  { to: '/config', label: 'Rules config', roles: ['COLLECTOR', 'MANAGER', 'HEAD', 'AUDITOR'] },
  { to: '/audit', label: 'Audit trail', roles: ['MANAGER', 'HEAD', 'AUDITOR'] },
];

const ROLE_LABEL = { COLLECTOR: 'Collector', MANAGER: 'Collections Manager', HEAD: 'Head of Collections', AUDITOR: 'Auditor' };

export default function Layout({ children }) {
  const s = getSession();
  const navigate = useNavigate();
  const logout = () => {
    setSession(null);
    navigate('/login');
  };
  return (
    <div className="shell">
      <nav className="side">
        <div className="brand">RecoverPlus</div>
        {NAV.filter((n) => n.roles.includes(s.user.role)).map((n) => (
          <NavLink key={n.to} to={n.to} className={({ isActive }) => (isActive ? 'nav active' : 'nav')}>
            {n.label}
          </NavLink>
        ))}
        <div className="spacer" />
        <div className="who">
          <strong>{s.user.fullName || s.user.userId}</strong>
          <span>{s.user.userId} · {ROLE_LABEL[s.user.role]}</span>
          {s.user.queue && <span>Queue {s.user.queue}{s.user.city ? ` · ${s.user.city}` : ''}</span>}
          <button className="link" onClick={logout}>Log out</button>
        </div>
      </nav>
      <main className="main">{children}</main>
    </div>
  );
}
