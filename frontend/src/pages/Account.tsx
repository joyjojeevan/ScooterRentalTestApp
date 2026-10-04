import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api';
import { useAuth } from '../auth';
import { Empty, ErrorAlert, Loading } from '../components/ui';
import { dateTime } from '../format';
import { useAction, useLoad } from '../hooks';
import type { Notification, User } from '../types';

export function Notifications() {
  const { data, error, loading, reload } = useLoad(() => api.get<Notification[]>('/notifications'));
  const action = useAction();

  return (
    <div className="stack" style={{ maxWidth: 760 }}>
      <div className="row between">
        <h1>Notifications</h1>
        <button className="small" disabled={action.busy} onClick={() => action.run(() => api.post('/notifications/read-all')).then(reload)}>
          Mark all read
        </button>
      </div>
      <ErrorAlert error={error ?? action.error} />
      {loading && <Loading />}
      {data?.length === 0 && <Empty>No notifications yet.</Empty>}
      <div className="card">
        {data?.map((n) => (
          <div
            key={n.id}
            className={`notif ${n.read ? '' : 'unread'}`}
            onClick={() => !n.read && api.post(`/notifications/${n.id}/read`).then(reload)}
          >
            <div className="row between">
              <strong className="subject">{n.subject}</strong>
              <span className="muted small">{dateTime(n.createdAt)}</span>
            </div>
            <div className="small">{n.message}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function Profile() {
  const { user, refresh } = useAuth();
  const [form, setForm] = useState({ fullName: '', phone: '', country: '', idDocumentNumber: '', drivingLicenseNo: '' });
  const [saved, setSaved] = useState(false);
  const action = useAction();

  useEffect(() => {
    if (user)
      setForm({
        fullName: user.fullName,
        phone: user.phone ?? '',
        country: user.country ?? '',
        idDocumentNumber: user.idDocumentNumber ?? '',
        drivingLicenseNo: user.drivingLicenseNo ?? '',
      });
  }, [user]);

  if (!user) return <Loading />;
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => {
    setSaved(false);
    setForm({ ...form, [k]: e.target.value });
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const res = await action.run(() => api.put<User>('/me', form));
    if (res) {
      await refresh();
      setSaved(true);
    }
  };

  return (
    <div className="card stack" style={{ maxWidth: 560 }}>
      <h1>Profile</h1>
      <p className="muted" style={{ margin: 0 }}>
        {user.email}
      </p>
      <form className="stack" onSubmit={submit}>
        <div className="form-grid">
          <label>
            Full name
            <input value={form.fullName} onChange={set('fullName')} required />
          </label>
          <label>
            Mobile
            <input value={form.phone} onChange={set('phone')} required />
          </label>
          <label>
            Country
            <input value={form.country} onChange={set('country')} />
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
        {saved && <div className="alert success">Saved.</div>}
        <button className="primary" disabled={action.busy}>
          Save
        </button>
      </form>
    </div>
  );
}
