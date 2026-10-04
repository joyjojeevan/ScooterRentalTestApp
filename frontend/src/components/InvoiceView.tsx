import { date, money } from '../format';
import type { Invoice } from '../types';
import { StatusBadge } from './ui';

const TITLE: Record<Invoice['kind'], string> = {
  FINAL: 'Final invoice',
  RENTAL: 'Rental invoice',
  SETTLEMENT: 'Return settlement',
};

export default function InvoiceView({ invoice }: { invoice: Invoice }) {
  // Final invoices take their status from the booking's payment; earlier ones kept their own.
  const status = invoice.kind === 'FINAL' ? invoice.paymentStatus : invoice.status;
  return (
    <div className="card stack">
      <div className="row between">
        <div>
          <h3 style={{ margin: 0 }}>
            {TITLE[invoice.kind]} {invoice.invoiceNumber}
          </h3>
          <div className="muted small">
            Issued {date(invoice.issuedAt)} · {invoice.customerName}
          </div>
        </div>
        {status && <StatusBadge status={status} />}
      </div>
      <table>
        <thead>
          <tr>
            <th>Description</th>
            <th className="num">Qty</th>
            <th className="num">Unit</th>
            <th className="num">Amount</th>
          </tr>
        </thead>
        <tbody>
          {invoice.lines.map((l, i) => (
            <tr key={i}>
              <td>{l.description}</td>
              <td className="num">{Number(l.quantity).toLocaleString('en-LK', { maximumFractionDigits: 2 })}</td>
              <td className="num">{money(l.unitPrice)}</td>
              <td className="num">{money(l.amount)}</td>
            </tr>
          ))}
          {invoice.tax > 0 && (
            <tr>
              <td colSpan={3}>Tax</td>
              <td className="num">{money(invoice.tax)}</td>
            </tr>
          )}
          <tr>
            <td colSpan={3}>
              <strong>{invoice.total < 0 ? 'Refund due to customer' : 'Total'}</strong>
            </td>
            <td className="num">
              <strong>{money(Math.abs(invoice.total))}</strong>
            </td>
          </tr>
          {invoice.balanceDue != null && (
            <tr>
              <td colSpan={3}>Balance still due</td>
              <td className="num">{money(invoice.balanceDue)}</td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
