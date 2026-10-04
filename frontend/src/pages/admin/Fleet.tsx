import { useState } from 'react';
import { api } from '../../api';
import { ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { money } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { GearCategory, GearItem, Scooter, ScooterStatus } from '../../types';

const EMPTY_SCOOTER = {
  code: '',
  model: '',
  plateNumber: '',
  engineCc: '',
  hourlyRate: '',
  perKmRate: '',
  totalMileage: '0',
  imageUrl: '',
  description: '',
};

export function Fleet() {
  const list = useLoad(() => api.get<Scooter[]>('/admin/scooters'));
  const [editing, setEditing] = useState<Scooter | 'new' | null>(null);
  const action = useAction();

  const setStatus = async (s: Scooter, value: ScooterStatus) => {
    if (await action.run(() => api.patch(`/admin/scooters/${s.id}/status?value=${value}`))) list.reload();
  };

  // Leaving the fleet is a soft delete (SDS 8.3); history is kept and the scooter can be restored.
  const remove = async (s: Scooter) => {
    if (!window.confirm(`Remove ${s.code} from the fleet? It will no longer be bookable.`)) return;
    if (await action.run(() => api.delete(`/admin/scooters/${s.id}`))) list.reload();
  };

  const restore = async (s: Scooter) => {
    if (await action.run(() => api.post(`/admin/scooters/${s.id}/restore`))) list.reload();
  };

  return (
    <div className="stack">
      <div className="row between">
        <h1>Fleet</h1>
        <button className="primary" onClick={() => setEditing('new')}>
          Add scooter
        </button>
      </div>
      <ErrorAlert error={list.error ?? action.error} />
      {editing && (
        <ScooterForm
          scooter={editing === 'new' ? null : editing}
          onDone={() => {
            setEditing(null);
            list.reload();
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {list.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Model</th>
              <th>Plate</th>
              <th>Status</th>
              <th className="num">Per hour</th>
              <th className="num">Per km</th>
              <th className="num">Odometer</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.data?.map((s) => (
              <tr key={s.id}>
                <td>{s.code}</td>
                <td>{s.model}</td>
                <td>{s.plateNumber}</td>
                <td>
                  {s.deleted ? <span className="badge red">Removed</span> : <StatusBadge status={s.status} />}
                </td>
                <td className="num">{money(s.hourlyRate)}</td>
                <td className="num">{money(s.perKmRate)}</td>
                <td className="num">{s.totalMileage.toLocaleString('en-LK', { maximumFractionDigits: 2 })} km</td>
                <td className="right">
                  <div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button className="small" onClick={() => setEditing(s)}>
                      Edit
                    </button>
                    {s.deleted ? (
                      <button className="small" disabled={action.busy} onClick={() => restore(s)}>
                        Restore
                      </button>
                    ) : (
                      s.status !== 'RENTED' && (
                        <>
                          <select
                            aria-label={`Set status of ${s.code}`}
                            value={s.status}
                            disabled={action.busy}
                            onChange={(e) => setStatus(s, e.target.value as ScooterStatus)}
                          >
                            <option value="AVAILABLE">Available</option>
                            <option value="MAINTENANCE">Maintenance</option>
                          </select>
                          <button className="small danger" disabled={action.busy} onClick={() => remove(s)}>
                            Remove
                          </button>
                        </>
                      )
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

function ScooterForm({ scooter, onDone, onCancel }: { scooter: Scooter | null; onDone: () => void; onCancel: () => void }) {
  const [f, setF] = useState(
    scooter
      ? {
          code: scooter.code,
          model: scooter.model,
          plateNumber: scooter.plateNumber,
          engineCc: String(scooter.engineCc ?? ''),
          hourlyRate: String(scooter.hourlyRate),
          perKmRate: String(scooter.perKmRate),
          totalMileage: String(scooter.totalMileage),
          imageUrl: scooter.imageUrl ?? '',
          description: scooter.description ?? '',
        }
      : EMPTY_SCOOTER,
  );
  const action = useAction();
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });

  const submit = async () => {
    const body = {
      ...f,
      engineCc: f.engineCc ? Number(f.engineCc) : null,
      hourlyRate: Number(f.hourlyRate),
      perKmRate: Number(f.perKmRate),
      totalMileage: Number(f.totalMileage),
      imageUrl: f.imageUrl || null,
    };
    const res = await action.run(() =>
      scooter ? api.put(`/admin/scooters/${scooter.id}`, body) : api.post('/admin/scooters', body),
    );
    if (res) onDone();
  };

  return (
    <div className="card stack">
      <h2>{scooter ? `Edit ${scooter.code}` : 'New scooter'}</h2>
      <div className="form-grid">
        <label>
          Fleet code
          <input value={f.code} onChange={set('code')} placeholder="SRK-07" />
        </label>
        <label>
          Model
          <input value={f.model} onChange={set('model')} />
        </label>
        <label>
          Plate number
          <input value={f.plateNumber} onChange={set('plateNumber')} />
        </label>
        <label>
          Engine (cc)
          <input type="number" value={f.engineCc} onChange={set('engineCc')} />
        </label>
        <label>
          Hourly rate (LKR)
          <input type="number" min="0" step="0.01" value={f.hourlyRate} onChange={set('hourlyRate')} />
        </label>
        <label>
          Per-km rate (LKR)
          <input type="number" min="0" step="0.01" value={f.perKmRate} onChange={set('perKmRate')} />
        </label>
        <label>
          Total mileage (km)
          <input type="number" min="0" step="0.01" value={f.totalMileage} onChange={set('totalMileage')} />
        </label>
        <label>
          Image URL
          <input value={f.imageUrl} onChange={set('imageUrl')} />
        </label>
      </div>
      <label>
        Description
        <input value={f.description} onChange={set('description')} />
      </label>
      <ErrorAlert error={action.error} />
      <div className="row">
        <button className="primary" disabled={action.busy} onClick={submit}>
          Save
        </button>
        <button onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

const CATEGORIES: GearCategory[] = ['CAMPING', 'SAFETY', 'LUGGAGE', 'ACCESSORY'];

export function Gear() {
  const list = useLoad(() => api.get<GearItem[]>('/admin/gear'));
  const [editing, setEditing] = useState<GearItem | 'new' | null>(null);

  return (
    <div className="stack">
      <div className="row between">
        <h1>Camping & travel gear</h1>
        <button className="primary" onClick={() => setEditing('new')}>
          Add item
        </button>
      </div>
      <ErrorAlert error={list.error} />
      {editing && (
        <GearForm
          item={editing === 'new' ? null : editing}
          onDone={() => {
            setEditing(null);
            list.reload();
          }}
          onCancel={() => setEditing(null)}
        />
      )}
      {list.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th>Category</th>
              <th className="num">Per day</th>
              <th className="num">Stock</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {list.data?.map((g) => (
              <tr key={g.id}>
                <td>
                  {g.name}
                  <div className="muted small">{g.description}</div>
                </td>
                <td>{g.category.toLowerCase()}</td>
                <td className="num">{money(g.dailyRate)}</td>
                <td className="num">{g.totalQuantity}</td>
                <td>{g.active ? <span className="badge green">Active</span> : <span className="badge">Hidden</span>}</td>
                <td className="right">
                  <button className="small" onClick={() => setEditing(g)}>
                    Edit
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

function GearForm({ item, onDone, onCancel }: { item: GearItem | null; onDone: () => void; onCancel: () => void }) {
  const [f, setF] = useState({
    name: item?.name ?? '',
    category: item?.category ?? ('CAMPING' as GearCategory),
    description: item?.description ?? '',
    dailyRate: String(item?.dailyRate ?? ''),
    totalQuantity: String(item?.totalQuantity ?? '1'),
    active: item?.active ?? true,
  });
  const action = useAction();

  const submit = async () => {
    const body = { ...f, dailyRate: Number(f.dailyRate), totalQuantity: Number(f.totalQuantity) };
    const res = await action.run(() => (item ? api.put(`/admin/gear/${item.id}`, body) : api.post('/admin/gear', body)));
    if (res) onDone();
  };

  return (
    <div className="card stack">
      <h2>{item ? `Edit ${item.name}` : 'New gear item'}</h2>
      <div className="form-grid">
        <label>
          Name
          <input value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
        </label>
        <label>
          Category
          <select value={f.category} onChange={(e) => setF({ ...f, category: e.target.value as GearCategory })}>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c.toLowerCase()}
              </option>
            ))}
          </select>
        </label>
        <label>
          Daily rate (LKR)
          <input type="number" value={f.dailyRate} onChange={(e) => setF({ ...f, dailyRate: e.target.value })} />
        </label>
        <label>
          Units in stock
          <input type="number" value={f.totalQuantity} onChange={(e) => setF({ ...f, totalQuantity: e.target.value })} />
        </label>
      </div>
      <label>
        Description
        <input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
      </label>
      <label className="inline">
        <input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />
        Offer to customers
      </label>
      <ErrorAlert error={action.error} />
      <div className="row">
        <button className="primary" disabled={action.busy} onClick={submit}>
          Save
        </button>
        <button onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}
