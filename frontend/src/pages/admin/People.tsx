import { useState } from 'react';
import { api } from '../../api';
import { ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { date, dateTime } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { NotificationLog, User } from '../../types';

export function Customers() {
  const list = useLoad(() => api.get<User[]>('/admin/customers'));
  const [search, setSearch] = useState('');
  const action = useAction();
  const q = search.trim().toLowerCase();
  const rows = (list.data ?? []).filter(
    (u) => !q || u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q) || (u.phone ?? '').includes(q),
  );

  const toggle = async (u: User) => {
    if (!window.confirm(`${u.active ? 'Block' : 'Unblock'} ${u.fullName}?`)) return;
    if (await action.run(() => api.patch(`/admin/customers/${u.id}/active?value=${!u.active}`))) list.reload();
  };

  return (
    <div className="stack">
      <h1>Customers</h1>
      <input placeholder="Search name, email, phone" value={search} onChange={(e) => setSearch(e.target.value)} style={{ maxWidth: 360 }} />
      <ErrorAlert error={list.error ?? action.error} />
      {list.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Contact</th>
              <th>Country</th>
              <th>ID / Licence</th>
              <th>Joined</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id}>
                <td>
                  {u.fullName}
                  {!u.active && (
                    <div>
                      <span className="badge red">Blocked</span>
                    </div>
                  )}
                </td>
                <td>
                  {u.email}
                  <div className="muted small">{u.phone}</div>
                </td>
                <td>{u.country ?? '—'}</td>
                <td className="small">
                  {u.idDocumentNumber ?? '—'}
                  <div className="muted">{u.drivingLicenseNo ?? 'No licence on file'}</div>
                </td>
                <td>{date(u.createdAt)}</td>
                <td className="right">
                  <button className={`small ${u.active ? 'danger' : ''}`} disabled={action.busy} onClick={() => toggle(u)}>
                    {u.active ? 'Block' : 'Unblock'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function NotificationsAdmin() {
  const log = useLoad(() => api.get<NotificationLog[]>('/admin/notifications'));
  const [subject, setSubject] = useState('');
  const [message, setMessage] = useState('');
  const [sent, setSent] = useState<number | null>(null);
  const action = useAction();

  const broadcast = async () => {
    if (!window.confirm('Send this to every active customer by in-app, email and SMS?')) return;
    const res = await action.run(() => api.post<{ recipients: number }>('/admin/notifications/broadcast', { subject, message }));
    if (res) {
      setSent(res.recipients);
      setSubject('');
      setMessage('');
      log.reload();
    }
  };

  return (
    <div className="stack">
      <h1>Notifications</h1>
      <div className="alert info small">
        Email and SMS providers are in <strong>mock mode</strong>: messages are logged by the backend, not delivered.
      </div>
      <div className="card stack">
        <h2>Broadcast to customers</h2>
        <label>
          Subject
          <input value={subject} onChange={(e) => setSubject(e.target.value)} placeholder="Esala Perahera road closures" />
        </label>
        <label>
          Message
          <textarea rows={3} value={message} onChange={(e) => setMessage(e.target.value)} />
        </label>
        <ErrorAlert error={action.error} />
        {sent != null && <div className="alert success">Sent to {sent} customer(s).</div>}
        <div>
          <button className="primary" disabled={!subject || !message || action.busy} onClick={broadcast}>
            Send
          </button>
        </div>
      </div>

      <h2>Recent messages</h2>
      {log.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Recipient</th>
              <th>Channel</th>
              <th>Subject</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {log.data?.map((n) => (
              <tr key={n.id}>
                <td>{dateTime(n.createdAt)}</td>
                <td>{n.recipient}</td>
                <td>{n.channel.replace('_', '-').toLowerCase()}</td>
                <td>{n.subject}</td>
                <td>
                  <StatusBadge status={n.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
