import { Navigate, Route, Routes } from 'react-router-dom';
import { AdminLayout, Layout, RequireAuth } from './components/Layout';
import { Notifications, Profile } from './pages/Account';
import { Login, Register } from './pages/Auth';
import BookingDetail from './pages/BookingDetail';
import Checkout from './pages/Checkout';
import Home from './pages/Home';
import MyBookings from './pages/MyBookings';
import ScooterBook from './pages/ScooterBook';
import Billing from './pages/admin/Billing';
import Bookings from './pages/admin/Bookings';
import Dashboard from './pages/admin/Dashboard';
import { Fleet, Gear } from './pages/admin/Fleet';
import Gps from './pages/admin/Gps';
import Maintenance from './pages/admin/Maintenance';
import { Customers, NotificationsAdmin } from './pages/admin/People';
import Reports from './pages/admin/Reports';

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Home />} />
        <Route path="scooters/:id" element={<ScooterBook />} />
        <Route path="login" element={<Login />} />
        <Route path="register" element={<Register />} />
        <Route path="bookings" element={<RequireAuth><MyBookings /></RequireAuth>} />
        <Route path="bookings/:id" element={<RequireAuth><BookingDetail /></RequireAuth>} />
        <Route path="bookings/:id/checkout" element={<RequireAuth><Checkout /></RequireAuth>} />
        <Route path="notifications" element={<RequireAuth><Notifications /></RequireAuth>} />
        <Route path="profile" element={<RequireAuth><Profile /></RequireAuth>} />
        <Route path="admin" element={<RequireAuth admin><AdminLayout /></RequireAuth>}>
          <Route index element={<Dashboard />} />
          <Route path="bookings" element={<Bookings />} />
          <Route path="fleet" element={<Fleet />} />
          <Route path="gps" element={<Gps />} />
          <Route path="maintenance" element={<Maintenance />} />
          <Route path="gear" element={<Gear />} />
          <Route path="billing" element={<Billing />} />
          <Route path="customers" element={<Customers />} />
          <Route path="notifications" element={<NotificationsAdmin />} />
          <Route path="reports" element={<Reports />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
