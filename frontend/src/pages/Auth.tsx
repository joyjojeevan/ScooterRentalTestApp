import { FormEvent, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth';
import { ErrorAlert } from '../components/ui';
import { useAction } from '../hooks';
import { isAdmin } from '../types';

function useRedirectTarget(fallback: string) {
  const location = useLocation();
  return (location.state as { from?: string } | null)?.from ?? fallback;
}

export function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const target = useRedirectTarget('/');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const action = useAction();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const user = await action.run(() => login(email, password));
    if (user) navigate(isAdmin(user.role) && target === '/' ? '/admin' : target, { replace: true });
  };

  return (
    <div className="card stack" style={{ maxWidth: 420, margin: '24px auto' }}>
      <h1>Log in</h1>
      <form className="stack" onSubmit={submit}>
        <label>
          Email
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        <ErrorAlert error={action.error} />
        <button className="primary" disabled={action.busy}>
          {action.busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
      <div className="alert info small">
        Demo accounts: <code>demo@example.com</code> / <code>Demo@12345</code> (customer),{' '}
        <code>admin@scooterrentkandy.lk</code> / <code>Admin@12345</code> (admin).
      </div>
      <p className="muted small">
        New here? <Link to="/register" state={{ from: target }}>Create an account</Link>
      </p>
    </div>
  );
}

export function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const target = useRedirectTarget('/');
  const [form, setForm] = useState({
    fullName: '',
    email: '',
    phone: '',
    password: '',
    country: '',
    idDocumentNumber: '',
    drivingLicenseNo: '',
  });
  const action = useAction();
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...form, [k]: e.target.value });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const user = await action.run(() => register(form));
    if (user) navigate(target, { replace: true });
  };

  return (
    <div className="card stack" style={{ maxWidth: 560, margin: '24px auto' }}>
      <h1>Create your account</h1>
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <label>
            Full name (as on your ID)
            <input autoComplete="name" value={form.fullName} onChange={set('fullName')} required />
          </label>
          <label>
            Email
            <input type="email" autoComplete="email" value={form.email} onChange={set('email')} required />
          </label>
          <label>
            Mobile (WhatsApp)
            <input type="tel" autoComplete="tel" value={form.phone} onChange={set('phone')} required placeholder="+94 77 …" />
          </label>
          <label>
            Password (8+ characters)
            <input type="password" autoComplete="new-password" minLength={8} value={form.password} onChange={set('password')} required />
          </label>
          <label>
            Country
            <input autoComplete="country-name" value={form.country} onChange={set('country')} />
          </label>
          <label>
            NIC / Passport no.
            <input value={form.idDocumentNumber} onChange={set('idDocumentNumber')} />
          </label>
          <label>
            Driving licence / IDP no.
            <input value={form.drivingLicenseNo} onChange={set('drivingLicenseNo')} />
          </label>
        </div>
        <ErrorAlert error={action.error} />
        <button className="primary" disabled={action.busy}>
          {action.busy ? 'Creating…' : 'Create account'}
        </button>
      </form>
      <p className="muted small">
        Already have an account? <Link to="/login" state={{ from: target }}>Log in</Link>
      </p>
    </div>
  );
}
