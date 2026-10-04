import { useState } from 'react';
import { api } from '../../api';
import { ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { date, money, today } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { MaintenanceRecord, Scooter } from '../../types';

export default function Maintenance() {
  const list = useLoad(() => api.get<MaintenanceRecord[]>('/admin/maintenance'));
  const scooters = useLoad(() => api.get<Scooter[]>('/admin/scooters'));
  const [form, setForm] = useState({ scooterId: '', type: 'SERVICE', description: '', scheduledDate: today(), startNow: false });
  const [completing, setCompleting] = useState<MaintenanceRecord | null>(null);
  const [cost, setCost] = useState('');
  const [odo, setOdo] = useState('');
  const action = useAction();

  const create = async () => {
    const res = await action.run(() => api.post('/admin/maintenance', form));
    if (res) {
      setForm({ ...form, description: '' });
      list.reload();
    }
  };

  const act = async (m: MaintenanceRecord, verb: 'start' | 'cancel') => {
    if (await action.run(() => api.post(`/admin/maintenance/${m.id}/${verb}`))) list.reload();
  };

  const complete = async () => {
    if (!completing) return;
    const res = await action.run(() =>
      api.post(`/admin/maintenance/${completing.id}/complete`, {
        cost: cost ? Number(cost) : null,
        odometerKm: odo ? Number(odo) : null,
      }),
    );
    if (res) {
      setCompleting(null);
      setCost('');
      setOdo('');
      list.reload();
    }
  };

  return (
    <div className="stack">
      <h1>Maintenance</h1>
      <p className="muted" style={{ margin: 0 }}>
        Routine services are auto-scheduled every 3,000 km when a scooter is returned. Starting work takes the scooter out
        of service; completing it puts it back.
      </p>
      <div className="card stack">
        <h2>Log work</h2>
        <div className="form-grid">
          <label>
            Scooter
            <select value={form.scooterId} onChange={(e) => setForm({ ...form, scooterId: e.target.value })}>
              <option value="">Choose…</option>
              {scooters.data?.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} · {s.model} ({s.status.toLowerCase()})
                </option>
              ))}
            </select>
          </label>
          <label>
            Type
            <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })}>
              <option value="SERVICE">Service</option>
              <option value="REPAIR">Repair</option>
              <option value="INSPECTION">Inspection</option>
            </select>
          </label>
          <label>
            Scheduled for
            <input type="date" value={form.scheduledDate} onChange={(e) => setForm({ ...form, scheduledDate: e.target.value })} />
          </label>
        </div>
        <label>
          Description
          <input value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </label>
        <label className="inline">
          <input type="checkbox" checked={form.startNow} onChange={(e) => setForm({ ...form, startNow: e.target.checked })} />
          Start now (take scooter off the road)
        </label>
        <ErrorAlert error={action.error} />
        <div>
          <button className="primary" disabled={!form.scooterId || !form.description || action.busy} onClick={create}>
            Save
          </button>
        </div>
      </div>

      {completing && (
        <div className="card stack">
          <h2>
            Complete: {completing.scooterCode} {completing.type.toLowerCase()}
          </h2>
          <div className="form-grid">
            <label>
              Cost (LKR)
              <input type="number" min={0} value={cost} onChange={(e) => setCost(e.target.value)} />
            </label>
            <label>
              Odometer (km)
              <input type="number" value={odo} onChange={(e) => setOdo(e.target.value)} placeholder="unchanged" />
            </label>
          </div>
          <div className="row">
            <button className="primary" disabled={action.busy} onClick={complete}>
              Mark done
            </button>
            <button onClick={() => setCompleting(null)}>Close</button>
          </div>
        </div>
      )}

      {list.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Scooter</th>
              <th>Type</th>
              <th>Description</th>
              <th>Status</th>
              <th>Scheduled</th>
              <th className="num">Cost</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.data?.map((m) => (
              <tr key={m.id}>
                <td>
                  {m.scooterCode}
                  <div className="muted small">{m.scooterModel}</div>
                </td>
                <td>{m.type.toLowerCase()}</td>
                <td style={{ whiteSpace: 'pre-wrap', maxWidth: 320 }}>{m.description}</td>
                <td>
                  <StatusBadge status={m.status} />
                </td>
                <td>
                  {date(m.scheduledDate)}
                  {m.completedDate && <div className="muted small">done {date(m.completedDate)}</div>}
                </td>
                <td className="num">{money(m.cost)}</td>
                <td className="right">
                  <div className="row" style={{ justifyContent: 'flex-end' }}>
                    {m.status === 'SCHEDULED' && (
                      <button className="small" disabled={action.busy} onClick={() => act(m, 'start')}>
                        Start
                      </button>
                    )}
                    {(m.status === 'SCHEDULED' || m.status === 'IN_PROGRESS') && (
                      <>
                        <button className="small primary" onClick={() => setCompleting(m)}>
                          Complete
                        </button>
                        <button className="small danger" disabled={action.busy} onClick={() => act(m, 'cancel')}>
                          Cancel
                        </button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
