import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import InvoiceView from '../../components/InvoiceView';
import { ErrorAlert, Loading, StatusBadge } from '../../components/ui';
import { date, dateTime, money } from '../../format';
import { useAction, useLoad } from '../../hooks';
import type { Invoice, Payment } from '../../types';

export default function Billing() {
  const [tab, setTab] = useState<'payments' | 'invoices'>('payments');
  const payments = useLoad(() => api.get<Payment[]>('/admin/payments'));
  const invoices = useLoad(() => api.get<Invoice[]>('/admin/invoices'));
  const [open, setOpen] = useState<Invoice | null>(null);
  const action = useAction();

  const viewInvoice = async (id: string) => {
    const inv = await action.run(() => api.get<Invoice>(`/invoices/${id}`));
    if (inv) setOpen(inv);
  };

  return (
    <div className="stack">
      <h1>Payments & billing</h1>
      <div className="alert info small">
        Payment provider: <strong>mock (test mode)</strong>, Stripe-shaped: one payment per booking, card details never
        reach this system. No real cards are charged.
      </div>
      <div className="row">
        <button className={tab === 'payments' ? 'primary' : ''} onClick={() => setTab('payments')}>
          Payments
        </button>
        <button className={tab === 'invoices' ? 'primary' : ''} onClick={() => setTab('invoices')}>
          Invoices
        </button>
      </div>
      <ErrorAlert error={payments.error ?? invoices.error ?? action.error} />
      {open && (
        <div className="stack">
          <InvoiceView invoice={open} />
          <div>
            <button onClick={() => setOpen(null)}>Close invoice</button>
          </div>
        </div>
      )}

      {tab === 'payments' && (
        <>
          {payments.loading && <Loading />}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Paid</th>
                  <th>Booking</th>
                  <th>Customer</th>
                  <th>Method</th>
                  <th>Status</th>
                  <th className="num">Amount</th>
                </tr>
              </thead>
              <tbody>
                {payments.data?.map((p) => (
                  <tr key={p.id}>
                    <td>{p.paidAt ? dateTime(p.paidAt) : <span className="muted">not paid</span>}</td>
                    <td>
                      <Link to={`/bookings/${p.bookingId}`}>{p.bookingReference}</Link>
                    </td>
                    <td>{p.customerName}</td>
                    <td>
                      {p.method.toLowerCase()}
                      {p.cardLast4 && <span className="muted small"> ****{p.cardLast4}</span>}
                    </td>
                    <td>
                      <StatusBadge status={p.status} />
                      {p.balanceDue != null && <div className="badge red">Due {money(p.balanceDue)}</div>}
                      {p.failureReason && <div className="muted small">{p.failureReason}</div>}
                    </td>
                    <td className="num">{money(p.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {tab === 'invoices' && (
        <>
          {invoices.loading && <Loading />}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Invoice</th>
                  <th>Issued</th>
                  <th>Booking</th>
                  <th>Customer</th>
                  <th>Kind</th>
                  <th>Status</th>
                  <th className="num">Total</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {invoices.data?.map((inv) => (
                  <tr key={inv.id}>
                    <td>{inv.invoiceNumber}</td>
                    <td>{date(inv.issuedAt)}</td>
                    <td>
                      <Link to={`/bookings/${inv.bookingId}`}>{inv.bookingReference}</Link>
                    </td>
                    <td>{inv.customerName}</td>
                    <td>{inv.kind.toLowerCase()}</td>
                    <td>
                      {(inv.kind === 'FINAL' ? inv.paymentStatus : inv.status) && (
                        <StatusBadge status={(inv.kind === 'FINAL' ? inv.paymentStatus : inv.status)!} />
                      )}
                    </td>
                    <td className="num">{money(inv.total)}</td>
                    <td className="right">
                      <div className="row" style={{ justifyContent: 'flex-end' }}>
                        <button className="small" onClick={() => viewInvoice(inv.id)}>
                          View
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
