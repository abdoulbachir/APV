// The donor giving flow of milestone 2B (first slice): report a gift, read the receipt with the
// reference and the one-time tracking code, optionally attach a screenshot, and look up a gift by
// its tracking code. All four operations take any session state and no account. Staff review and
// correction screens are a later slice. No payment is ever taken here.
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { donations, newIdempotencyKey, DonationError } from '../api/donation-client';
import type { DonationOperations } from '../api/generated/identity-api';
import { useAsync } from '../hooks/useAsync';
import { useI18n } from '../i18n/I18nContext';
import { formatMoney, formatDate } from '../utils/format';
import { LoadingState } from '../components/States';
import { donationCopy } from './copy';
import './donations.css';

type Method = DonationOperations['listPaymentMethods']['response']['methods'][number];
type Receipt = DonationOperations['reportDonation']['response'];
type Status = DonationOperations['getDonationStatus']['response'];

const FUND = 'FUND';
const today = () => new Date().toISOString().slice(0, 10);
const addDays = (days: number) => { const d = new Date(); d.setUTCDate(d.getUTCDate() + days); return d.toISOString().slice(0, 10); };

function CopyButton({ value, label, done }: { value: string; label: string; done: string }) {
  const [copied, setCopied] = useState(false);
  return <button type="button" className="donation-copy" onClick={async () => {
    try { await navigator.clipboard.writeText(value); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch { /* ignore */ }
  }}>{copied ? done : label}</button>;
}

function Recipient({ method }: { method: Method }) {
  const { locale } = useI18n(); const c = donationCopy(locale);
  return <div className="donation-recipient">
    <p className="donation-instructions">{method.instructions[locale]}</p>
    <dl>{method.recipient.map((field, i) => <div key={i}>
      <dt>{field.labels[locale]}</dt>
      <dd>
        {field.format === 'link'
          ? <a href={field.value} target="_blank" rel="noopener noreferrer">{c.give.openLink}</a>
          : <><span className="donation-value">{field.value}</span><CopyButton value={field.value} label={c.give.copy} done={c.give.copied} /></>}
      </dd>
    </div>)}</dl>
    <p className="donation-note">{c.give.noCardNote}</p>
  </div>;
}

/** The report form and, once reported, the receipt shown in place (the tracking code never leaves memory). */
export function GivingPage() {
  const { t, locale } = useI18n(); const c = donationCopy(locale);
  const [params] = useSearchParams();
  const caseParam = (params.get('case') ?? '').trim().toUpperCase();
  const target = caseParam && caseParam !== FUND ? caseParam : FUND;
  const methods = useAsync(() => donations.listMethods(locale), [locale]);
  const idempotencyKey = useRef(newIdempotencyKey());
  // Read when the form opens, before anything is typed; read again only after the server refused it.
  const token = useRef<Promise<string> | null>(null);
  const lease = () => { if (!token.current) { const reading = donations.token(locale); reading.catch(() => { token.current = null; }); token.current = reading; } return token.current; };
  useEffect(() => { void lease().catch(() => undefined); }, []);
  const [methodId, setMethodId] = useState<string | null>(null);
  const [mode, setMode] = useState<'named' | 'anonymous' | 'hidden'>('anonymous');
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState<Receipt | null>(null);

  useEffect(() => { document.title = c.give.title; }, [c]);
  useEffect(() => { const list = methods.data?.methods; if (list?.length && !methodId) setMethodId(list[0].id); }, [methods.data, methodId]);

  if (methods.loading && !methods.data) return <div className="donation-wrap"><LoadingState /></div>;
  if (receipt) return <ReceiptView receipt={receipt} target={target} />;

  const list = methods.data?.methods ?? [];
  const chosen = list.find(m => m.id === methodId) ?? null;

  if (methods.error) return <div className="donation-wrap"><section className="identity-panel">
    <p className="identity-eyebrow">{c.give.title}</p><h1>{c.give.title}</h1>
    <p className="identity-error" role="alert">{c.errors.loadMethods}</p>
    <button className="identity-primary" type="button" onClick={methods.retry}>{t.nav.give}</button>
  </section></div>;

  if (list.length === 0) return <div className="donation-wrap"><section className="identity-panel">
    <p className="identity-eyebrow">{c.give.title}</p><h1>{c.give.title}</h1><p>{c.give.notOpen}</p>
    <Link to="/cases">{t.nav.explore}</Link>
  </section></div>;

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy || !chosen) return;
    const form = event.currentTarget; const data = new FormData(form);
    const dollars = Number(String(data.get('amount')).replace(',', '.'));
    const amount = Math.round(dollars * 100);
    if (!Number.isFinite(dollars) || amount < 1 || amount > 10_000_000_000) { setError(new ApiError('validation_error', '', 400)); return; }
    const body = {
      caseCode: target, amount, paymentDetailsId: chosen.detailsId,
      paidOn: String(data.get('paidOn')), payerName: String(data.get('payerName')).trim(),
      ...(String(data.get('paymentReference')).trim() ? { paymentReference: String(data.get('paymentReference')).trim() } : {}),
      ...(String(data.get('contact')).trim() ? { contact: String(data.get('contact')).trim() } : {}),
      display: mode === 'named' ? { mode, publicName: String(data.get('publicName')).trim() } : { mode },
    };
    setBusy(true); setError(null);
    try { setReceipt(await donations.report(body, idempotencyKey.current, await lease(), locale)); }
    catch (e) {
      const problem = e instanceof ApiError ? e : new ApiError('network_error', '', 0); setError(problem);
      // The refused report is not sent again. A new token is read for the next time the person submits.
      if (problem.code === 'csrf_invalid') { token.current = null; void lease().catch(() => undefined); }
    }
    finally { setBusy(false); }
  }

  return <div className="donation-wrap"><section className="identity-panel">
    <p className="identity-eyebrow">{c.give.title}</p>
    <h1>{c.give.title} {target === FUND ? c.give.toFund : target}</h1>
    <p>{c.give.intro}</p>
    <p className="donation-switch">{target === FUND
      ? <Link to="/cases">{c.give.switchToCase}</Link>
      : <Link to="/give">{c.give.switchToFund}</Link>}</p>
    {error && <div className="identity-error" role="alert" tabIndex={-1}><p>{errorText(c, error.code)}</p>
      {error instanceof DonationError && error.fields.length > 0 && <ul className="donation-problems">{error.fields.map((f, i) =>
        <li key={i}>{c.fieldNames[f.name] ?? f.name}: {c.fieldProblems[f.code] ?? c.fieldProblems.invalid_value}</li>)}</ul>}</div>}
    <form onSubmit={onSubmit}><fieldset disabled={busy}>
      <fieldset className="donation-methods"><legend>{c.give.chooseMethod}</legend>
        {list.map(m => <label key={m.id} className="donation-method">
          <input type="radio" name="method" value={m.id} checked={methodId === m.id} onChange={() => setMethodId(m.id)} />
          <span>{m.labels[locale]}</span>
        </label>)}
      </fieldset>
      {chosen && <Recipient method={chosen} />}

      <div className="identity-field"><label htmlFor="amount">{c.give.amount}</label>
        <input id="amount" name="amount" inputMode="decimal" required autoComplete="off" />
        <small>{c.give.amountHint}</small></div>

      <div className="identity-field"><label htmlFor="paidOn">{c.give.paidOn}</label>
        <input id="paidOn" name="paidOn" type="date" required defaultValue={today()} min={addDays(-90)} max={addDays(1)} /></div>

      <div className="identity-field"><label htmlFor="payerName">{c.give.payerName}</label>
        <input id="payerName" name="payerName" required autoComplete="name" />
        <small>{c.give.payerNameHint}</small></div>

      <div className="identity-field"><label htmlFor="paymentReference">{c.give.reference}</label>
        <input id="paymentReference" name="paymentReference" autoComplete="off" />
        <small>{c.give.referenceHint}</small></div>

      <div className="identity-field"><label htmlFor="contact">{c.give.contact}</label>
        <input id="contact" name="contact" autoComplete="off" />
        <small>{c.give.contactHint}</small></div>

      <fieldset className="donation-display"><legend>{c.give.display}</legend>
        {(['named', 'anonymous', 'hidden'] as const).map(m => <label key={m} className="donation-method">
          <input type="radio" name="display" value={m} checked={mode === m} onChange={() => setMode(m)} />
          <span>{c.give[m]}</span>
        </label>)}
        {mode === 'named' && <div className="identity-field"><label htmlFor="publicName">{c.give.namedField}</label>
          <input id="publicName" name="publicName" required maxLength={60} />
          <small>{c.give.namedConsent}</small></div>}
      </fieldset>

      <button className="identity-primary" type="submit">{busy ? c.give.submitting : c.give.submit}</button>
    </fieldset></form>
  </section></div>;
}

function ReceiptView({ receipt, target }: { receipt: Receipt; target: string }) {
  const { locale } = useI18n(); const c = donationCopy(locale);
  const [file, setFile] = useState<File | null>(null);
  const [state, setState] = useState<'idle' | 'busy' | 'done' | 'failed'>('idle');
  useEffect(() => { document.title = c.receipt.title; }, [c]);

  async function attach() {
    if (!file || !receipt.evidenceUpload) return;
    setState('busy');
    try { await donations.attachEvidence(receipt.evidenceUpload.token, file, locale); setState('done'); }
    catch { setState('failed'); }
  }

  return <div className="donation-wrap"><section className="identity-panel">
    <p className="identity-eyebrow">{target === FUND ? c.give.toFund : c.give.toCase}</p>
    <h1>{c.receipt.title}</h1>
    <p>{c.receipt.body}</p>
    <dl className="donation-codes">
      <div><dt>{c.receipt.reference}</dt><dd><code>{receipt.reference}</code></dd></div>
      <div><dt>{c.receipt.trackingCode}</dt><dd><code className="donation-tracking">{receipt.trackingCode}</code>
        <CopyButton value={receipt.trackingCode} label={c.receipt.copyCode} done={c.receipt.copied} /></dd></div>
    </dl>
    <p className="donation-keep" role="note">{c.receipt.keepCode}</p>

    {receipt.evidenceUpload && state !== 'done' && <div className="donation-evidence">
      <p>{c.receipt.evidenceOffer}</p>
      <input type="file" accept="image/jpeg,image/png" aria-label={c.receipt.chooseFile} onChange={e => setFile(e.target.files?.[0] ?? null)} />
      <button type="button" className="identity-primary" disabled={!file || state === 'busy'} onClick={attach}>
        {state === 'busy' ? c.receipt.attaching : c.receipt.attach}</button>
      {state === 'failed' && <p className="identity-error" role="alert">{c.receipt.evidenceFailed}</p>}
    </div>}
    {state === 'done' && <p className="donation-ok" role="status">{c.receipt.attached}</p>}

    <p><Link to="/track">{c.receipt.trackLink}</Link></p>
    <p><Link to="/">{c.receipt.done}</Link></p>
  </section></div>;
}

export function StatusPage() {
  const { locale } = useI18n(); const c = donationCopy(locale);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [status, setStatus] = useState<Status | null>(null);
  useEffect(() => { document.title = c.status.title; }, [c]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (busy) return;
    const code = String(new FormData(event.currentTarget).get('code')).trim();
    setBusy(true); setError(null); setStatus(null);
    try { setStatus(await donations.status(code, locale)); }
    catch (e) { setError(e instanceof ApiError ? e : new ApiError('network_error', '', 0)); }
    finally { setBusy(false); }
  }

  return <div className="donation-wrap"><section className="identity-panel">
    <p className="identity-eyebrow">{c.status.title}</p><h1>{c.status.title}</h1><p>{c.status.intro}</p>
    <form onSubmit={onSubmit}><fieldset disabled={busy}>
      <div className="identity-field"><label htmlFor="code">{c.status.code}</label>
        <input id="code" name="code" required autoComplete="off" autoCapitalize="characters" spellCheck={false}
          aria-invalid={error ? true : undefined} aria-describedby={error ? 'code-error' : undefined} /></div>
      {error && <p className="identity-field-error" id="code-error" role="alert">{errorText(c, error.code)}</p>}
      <button className="identity-primary" type="submit">{busy ? c.status.checking : c.status.check}</button>
    </fieldset></form>
    {status && <StatusResult status={status} />}
  </section></div>;
}

function StatusResult({ status }: { status: Status }) {
  const { locale } = useI18n(); const c = donationCopy(locale);
  const fmt = (s: string, v: Record<string, string>) => s.replace(/\{(\w+)\}/g, (_, k) => v[k] ?? '');
  return <div className="donation-status" role="status">
    <p className="donation-reference">{fmt(c.status.reportedAmount, { amount: formatMoney(status.reportedAmount, locale) })} · {status.reference}</p>
    {status.state === 'reported' && <p>{c.status.reported}</p>}
    {status.state === 'confirmed' && <>
      <p>{fmt(c.status.confirmedOn, { date: status.decidedAt ? formatDate(status.decidedAt, locale, true) : '', amount: formatMoney(status.confirmedAmount ?? 0, locale) })}</p>
      {status.confirmedCaseCode && <p>{fmt(c.status.confirmedCase, { case: status.confirmedCaseCode })}</p>}
      {status.netAmount !== null && status.netAmount !== status.confirmedAmount && <p>{fmt(c.status.net, { amount: formatMoney(status.netAmount, locale) })}</p>}
    </>}
    {status.state === 'rejected' && <>
      <p>{c.status.rejected}</p>
      <p>{status.rejectionReason ? c.status[`reason_${status.rejectionReason}` as const] : c.status.reason_other}</p>
    </>}
    {status.evidenceAttached && <p className="donation-note">{c.status.evidenceAttached}</p>}
    <p className="donation-note">{c.status.contact}</p>
  </div>;
}

// Any error code resolves to a message, falling back to the generic one, as the contract says a
// client should treat an unknown code as a generic failure for its status.
function errorText(c: ReturnType<typeof donationCopy>, code: string): string {
  return c.errors[code] ?? c.errors.generic;
}
