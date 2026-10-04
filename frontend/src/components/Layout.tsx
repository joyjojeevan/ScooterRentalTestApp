import { useEffect, useState } from 'react';
import { Link, NavLink, Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api';
import { useAuth } from '../auth';
import { isAdmin } from '../types';

export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [unread, setUnread] = useState(0);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    const load = () =>
      api
        .get<{ count: number }>('/notifications/unread-count')
        .then((r) => !cancelled && setUnread(r.count))
        .catch(() => {});
    load();
    const t = setInterval(load, 30_000);
    return () => {
      cancelled = true;
      clearInterval(t);
    };
  }, [user, location.pathname]);

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand">
            <span aria-hidden>🛵</span> Scooter Rent Kandy
          </Link>
          <nav className="nav">
            <NavLink to="/" end>
              Scooters
            </NavLink>
            {user && <NavLink to="/bookings">My bookings</NavLink>}
            {user && (
              <NavLink to="/notifications">
                Notifications{unread > 0 && <span className="pill-count">{unread}</span>}
              </NavLink>
            )}
            {isAdmin(user?.role) && <NavLink to="/admin">Admin</NavLink>}
          </nav>
          <div className="topbar-right">
            {user ? (
              <>
                <NavLink to="/profile" className="muted small">
                  {user.fullName}
                </NavLink>
                <button
                  className="small"
                  onClick={() => {
                    logout();
                    navigate('/');
                  }}
                >
                  Log out
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="btn small">
                  Log in
                </Link>
                <Link to="/register" className="btn small primary">
                  Sign up
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="container">
        <Outlet />
      </main>
    </>
  );
}

export function RequireAuth({ admin, children }: { admin?: boolean; children: JSX.Element }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <p className="muted">Loading…</p>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (admin && !isAdmin(user.role)) return <Navigate to="/" replace />;
  return children;
}

const ADMIN_LINKS = [
  ['/admin', 'Dashboard'],
  ['/admin/bookings', 'Bookings'],
  ['/admin/fleet', 'Fleet'],
  ['/admin/gps', 'Live GPS'],
  ['/admin/maintenance', 'Maintenance'],
  ['/admin/gear', 'Camping gear'],
  ['/admin/billing', 'Payments & billing'],
  ['/admin/customers', 'Customers'],
  ['/admin/notifications', 'Notifications'],
  ['/admin/reports', 'Reports'],
] as const;

export function AdminLayout() {
  return (
    <div className="admin-shell">
      <aside className="sidebar">
        {ADMIN_LINKS.map(([to, label]) => (
          <NavLink key={to} to={to} end={to === '/admin'}>
            {label}
          </NavLink>
        ))}
      </aside>
      <section>
        <Outlet />
      </section>
    </div>
  );
}
