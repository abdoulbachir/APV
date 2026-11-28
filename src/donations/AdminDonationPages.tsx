// Donation review screens of milestone 2B (donation-lifecycle.md sections 6 to 8): the queue, one
// gift with everything recorded about it, confirm, reject, reopen, reversal, refund, restricting
// the listing, deleting evidence, and manual entry. They are private screens: they render only
// for a fully signed-in session, the server checks donations.confirm on every request, and a
// refused request is never resent by the page. Amounts and cases are typed by the reviewer and
// never prefilled (6.3).
import { createContext, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { donationAdmin, readToken, DonationAdminError, type DonationDetail, type DonationPage } from '../api/donation-admin-client';
import { newIdempotencyKey } from '../api/donation-client';
import { Guard } from '../identity/IdentityPages';
import { useIdentitySession } from '../identity/SessionProvider';
import { useI18n } from '../i18n/I18nContext';
import { formatDate, formatMoney } from '../utils/format';
import { adminCopy } from './admin-copy';
import './donations.css';

type Copy = ReturnType<typeof adminCopy>;
const PERMISSION = 'donations.confirm';
const fill = (text: string, values: Record<string, string>) => text.replace(/\{(\w+)\}/g, (_, key) => values[key] ?? '');
const text = (data: FormData, name: string) => String(data.get(name) ?? '').trim();
const optional = (data: FormData, name: string) => { const value = text(data, name); return value ? { [name]: value } : {}; };
/** Dollars typed by the reviewer to integer cents; NaN when it is not an amount. */
const cents = (value: string) => { const n = Number(value.replace(',', '.')); return Number.isFinite(n) ? Math.round(n * 100) : NaN; };
const today = () => new Date().toISOString().slice(0, 10);

function errorMessage(c: Copy, error: DonationAdminError): string {
  if (error.code === 'validation_error') return fill(c.errors.validation_error, { fields: error.fields.map(f => `${f.name} (${f.code})`).join(', ') || '?' });
  return c.errors[error.code] ?? c.errors.generic;
}

// The token of the session a review screen was opened with. It is read once, when the screen
// opens and before anything is typed, and every POST from that screen carries it: a form is never
// sent with a token fetched at the last moment for whatever session the browser then has (I&S 9.1).
const TokenContext = createContext<{ current: Promise<string> | null }>({ current: null });

/** Runs one request at a time; an error that says the session changed makes the session re-read itself. */
function useAction() {
  const { refresh } = useIdentitySession(); const lease = useContext(TokenContext);
  const [busy, setBusy] = useState(false); const working = useRef(false);
  const [error, setError] = useState<DonationAdminError | null>(null);
  async function run<T>(work: (token: string) => Promise<T>): Promise<T | undefined> {
    // The ref stops a second press that arrives before the disabled state is drawn.
    if (busy || working.current) return undefined;
    working.current = true; setBusy(true); setError(null);
    try { if (!lease.current) throw new DonationAdminError('csrf_invalid', '', 403); return await work(await lease.current); }
    catch (caught) {
      const problem = caught instanceof DonationAdminError ? caught : new DonationAdminError('network_error', '', 0);
      setError(problem);
      if (problem.status === 401 || problem.code === 'csrf_invalid') void refresh();
      return undefined;
    } finally { working.current = false; setBusy(false); }
  }
  return { busy, error, run };
}

function Lease({ children }: { children: ReactNode }) {
  const { locale } = useI18n(); const lease = useRef<{ current: Promise<string> | null }>({ current: null });
  if (!lease.current.current) { const reading = readToken(locale); reading.catch(() => { lease.current.current = null; }); lease.current.current = reading; }
  return <TokenContext.Provider value={lease.current}>{children}</TokenContext.Provider>;
}
function Staff({ children }: { children: () => ReactNode }) {
  const { locale } = useI18n(); const c = adminCopy(locale);
  return <Guard allowed={['authenticated']}>{account => account && (account.permissions.includes(PERMISSION)
    ? <Lease><div className="donation-admin">{children()}</div></Lease>
    : <div className="donation-wrap"><section className="identity-panel"><p role="alert">{c.noPermission}</p><Link to="/account">{c.nav.account}</Link></section></div>)}</Guard>;
}

function Field({ name, label, hint, type = 'text', required = false, defaultValue, max }: { name: string; label: string; hint?: string; type?: string; required?: boolean; defaultValue?: string; max?: string }) {
  return <div className="identity-field"><label htmlFor={name}>{label}</label>
    <input id={name} name={name} type={type} required={required} defaultValue={defaultValue} max={max} autoComplete="off" aria-describedby={hint ? name + '-hint' : undefined} />
    {hint && <small id={name + '-hint'}>{hint}</small>}</div>;
}
function Choice({ name, label, options, required = true }: { name: string; label: string; options: Array<[string, string]>; required?: boolean }) {
  return <div className="identity-field"><label htmlFor={name}>{label}</label>
    <select id={name} name={name} required={required} defaultValue="">
      <option value="" disabled>—</option>{options.map(([value, shown]) => <option key={value} value={value}>{shown}</option>)}
    </select></div>;
}
function Problem({ c, error }: { c: Copy; error: DonationAdminError | null }) {
  return error ? <p className="identity-error" role="alert">{errorMessage(c, error)}</p> : null;
}

// ---------------------------------------------------------------- queue

export function ReviewQueuePage() { return <Staff>{() => <Queue />}</Staff>; }

function Queue() {
  const { locale } = useI18n(); const c = adminCopy(locale);
  const [params, setParams] = useSearchParams();
  const status = (['reported', 'confirmed', 'rejected', 'all'] as const).find(s => s === params.get('status')) ?? 'reported';
  const page = Math.max(1, Number(params.get('page')) || 1);
  const [data, setData] = useState<DonationPage | null>(null);
  const [error, setError] = useState<DonationAdminError | null>(null);
  useEffect(() => { document.title = c.queue.title; }, [c]);
  useEffect(() => {
    let alive = true; setData(null); setError(null);
    donationAdmin.list({ status, page }, locale).then(value => { if (alive) setData(value); },
      caught => { if (alive) setError(caught instanceof DonationAdminError ? caught : new DonationAdminError('network_error', '', 0)); });
    return () => { alive = false; };
  }, [status, page, locale]);

  return <section className="donation-admin-panel">
    <h1>{c.queue.title}</h1>
    {data && <p className="donation-pending" role="status">{fill(c.queue.pending, { count: String(data.pendingCount) })}</p>}
    <nav className="donation-tabs" aria-label={c.queue.title}>
      {(['reported', 'confirmed', 'rejected', 'all'] as const).map(s =>
        <button key={s} type="button" aria-pressed={status === s} onClick={() => setParams({ status: s })}>{c.queue[s]}</button>)}
    </nav>
    <Problem c={c} error={error} />
    {!data && !error && <p role="status">{c.busy}</p>}
    {data && data.items.length === 0 && <p>{c.queue.empty}</p>}
    {data && data.items.length > 0 && <ul className="donation-list">{data.items.map(item => <li key={item.id}>
      <Link to={`/admin/donations/${encodeURIComponent(item.id)}`}>
        <strong>{item.reference}</strong>
        <span>{formatMoney(item.confirmedAmount ?? item.reportedAmount ?? 0, locale)} · {item.caseCode}</span>
        <span>{c.state[item.state]} · {formatDate(item.decidedAt ?? item.reportedAt, locale, true)}</span>
        {item.payerName && <span>{item.payerName}</span>}
        <span className="donation-flags">
          {item.origin === 'staff_entry' && <em>{c.queue.staffEntry}</em>}
          {item.evidence === 'attached' && <em>{c.queue.evidence}</em>}
          {item.flags.map(flag => <em key={flag}>{c.flags[flag]}</em>)}
        </span>
      </Link></li>)}</ul>}
    {data && data.totalPages > 1 && <p className="donation-pager">
      <button type="button" disabled={page <= 1} onClick={() => setParams({ status, page: String(page - 1) })}>{c.queue.previous}</button>
      <span>{fill(c.queue.page, { page: String(data.page), pages: String(data.totalPages) })}</span>
      <button type="button" disabled={page >= data.totalPages} onClick={() => setParams({ status, page: String(page + 1) })}>{c.queue.next}</button>
    </p>}
    <p><Link to="/admin/donations/new">{c.nav.enter}</Link></p>
    <p><Link to="/account">{c.nav.account}</Link></p>
  </section>;
}

// ---------------------------------------------------------------- one gift

export function GiftPage() { return <Staff>{() => <Gift />}</Staff>; }

function Gift() {
  const { locale } = useI18n(); const c = adminCopy(locale);
  const { donationId = '' } = useParams();
  const [gift, setGift] = useState<DonationDetail | null>(null);
  const [loadError, setLoadError] = useState<DonationAdminError | null>(null);
  const [done, setDone] = useState(0);
  useEffect(() => {
    let alive = true; setGift(null); setLoadError(null);
    donationAdmin.get(donationId, locale).then(value => { if (alive) setGift(value); },
      caught => { if (alive) setLoadError(caught instanceof DonationAdminError ? caught : new DonationAdminError('network_error', '', 0)); });
    return () => { alive = false; };
  }, [donationId, locale]);
  useEffect(() => { if (gift) document.title = fill(c.detail.title, { reference: gift.reference }); }, [gift, c]);
  // Every successful action answers the gift as it now is; the forms are remounted with new keys.
  const accept = (next: DonationDetail) => { setGift(next); setDone(n => n + 1); };

  if (loadError) return <section className="donation-admin-panel"><Problem c={c} error={loadError} /><Link to="/admin/donations">{c.nav.queue}</Link></section>;
  if (!gift) return <section className="donation-admin-panel"><p role="status">{c.busy}</p></section>;
  const money = (value: number | null) => value === null ? '—' : formatMoney(value, locale);
  const date = (value: string | null) => value ? formatDate(value, locale, true) : '—';

  return <section className="donation-admin-panel">
    <p><Link to="/admin/donations">{c.nav.queue}</Link></p>
    <h1>{fill(c.detail.title, { reference: gift.reference })}</h1>
    <p className="donation-state"><strong>{c.state[gift.state]}</strong>
      <span className="donation-flags">{gift.origin === 'staff_entry' && <em>{c.queue.staffEntry}</em>}{gift.flags.map(flag => <em key={flag}>{c.flags[flag]}</em>)}</span></p>
    {done > 0 && <p className="donation-ok" role="status">{c.done}</p>}

    <h2>{c.detail.reported}</h2>
    <dl className="donation-facts">
      {gift.reported && <><dt>{c.detail.case}</dt><dd>{gift.reported.caseCode}</dd>
        <dt>{c.detail.amount}</dt><dd>{money(gift.reported.amount)}</dd>
        <dt>{c.detail.paidOn}</dt><dd>{date(gift.reported.paidOn)}</dd>
        <dt>{c.detail.reportedAt}</dt><dd>{date(gift.reported.at)}</dd></>}
      <dt>{c.detail.method}</dt><dd>{gift.method.id ?? gift.method.description ?? '—'}</dd>
      <dt>{c.detail.payer}</dt><dd>{gift.payerName ?? '—'}</dd>
      <dt>{c.detail.paymentReference}</dt><dd>{gift.paymentReference ?? '—'}</dd>
      <dt>{c.detail.contact}</dt><dd>{gift.contact ?? '—'}</dd>
      <dt>{c.detail.display}</dt><dd>{c.display[gift.display.mode]}{gift.display.publicName ? ` · ${gift.display.publicName}` : ''}</dd>
    </dl>
    {gift.linkedAccount && <p className="donation-note">{c.detail.linked}</p>}
    {gift.privateDetailsErasedAt && <p className="donation-note">{fill(c.detail.erased, { date: date(gift.privateDetailsErasedAt) })}</p>}
    {gift.shownDetails && <><h3>{c.detail.shown}</h3>
      <dl className="donation-facts">{gift.shownDetails.recipient.map((field, i) => <div key={i}><dt>{field.labels[locale]}</dt><dd>{field.value}</dd></div>)}</dl>
      {!gift.shownDetails.current && <p className="donation-note">{c.detail.supersededNote}</p>}</>}

    {gift.evidence !== 'none' && <><h2>{c.detail.evidence}</h2>
      {gift.evidence === 'attached' ? <Evidence c={c} gift={gift} onDone={accept} /> : <p className="donation-note">{c.detail.evidenceDeleted}</p>}</>}

    {gift.decision && <><h2>{c.detail.decision}</h2>
      {gift.decision.state === 'confirmed'
        ? <p>{fill(c.detail.confirmedFor, { amount: money(gift.decision.amount), case: gift.decision.caseCode ?? '—' })}</p>
        : <p>{fill(c.detail.rejectedBecause, { reason: gift.decision.rejectionReason ? c.reasons[gift.decision.rejectionReason] : '—' })}</p>}
      <dl className="donation-facts">
        {gift.decision.receivedOn && <><dt>{c.detail.receivedOn}</dt><dd>{date(gift.decision.receivedOn)}</dd></>}
        {gift.decision.providerReference && <><dt>{c.detail.providerReference}</dt><dd>{gift.decision.providerReference}</dd></>}
        {gift.decision.note && <><dt>{c.detail.note}</dt><dd>{gift.decision.note}</dd></>}
      </dl>
      <p className="donation-note">{fill(c.detail.by, { name: gift.decision.by.displayName, date: date(gift.decision.at) })}</p></>}

    {gift.state === 'confirmed' && <><h2>{c.detail.money}</h2>
      <p><strong>{c.detail.net}: {money(gift.netAmount)}</strong></p>
      {gift.corrections.length > 0 && <><h3>{c.detail.corrections}</h3><ul className="donation-plain">{gift.corrections.map(correction =>
        <li key={correction.ledgerEntryId}>{c.kinds[correction.kind]} · {money(correction.amount)} · {date(correction.at)}{correction.reason ? ` · ${(c.reasons as Record<string, string>)[correction.reason] ?? correction.reason}` : ''}</li>)}</ul></>}</>}

    {(gift.reentryOf || gift.reenteredAs) && <p className="donation-note">
      {gift.reentryOf && <>{c.detail.reentryOf} <Link to={`/admin/donations/${encodeURIComponent(gift.reentryOf)}`}>{gift.reentryOf}</Link></>}
      {gift.reenteredAs && <> {c.detail.reenteredAs} <Link to={`/admin/donations/${encodeURIComponent(gift.reenteredAs)}`}>{gift.reenteredAs}</Link></>}</p>}

    {gift.possibleDuplicates.length > 0 && <><h2>{c.detail.duplicates}</h2><ul className="donation-plain">{gift.possibleDuplicates.map(other =>
      <li key={other.id}><Link to={`/admin/donations/${encodeURIComponent(other.id)}`}>{other.reference}</Link> · {money(other.amount)} · {c.state[other.state]} · {date(other.reportedAt)}</li>)}</ul></>}

    {gift.state === 'reported' && <><ConfirmForm key={'c' + done} c={c} gift={gift} onDone={accept} /><RejectForm key={'r' + done} c={c} gift={gift} onDone={accept} /></>}
    {gift.state === 'rejected' && <ReopenForm c={c} gift={gift} onDone={accept} />}
    {gift.state === 'confirmed' && (gift.netAmount ?? 0) > 0 && <CorrectionForms key={'x' + done} c={c} gift={gift} onDone={accept} />}
    {gift.state === 'confirmed' && gift.display.mode !== 'hidden' && <ListingForm key={'l' + done} c={c} gift={gift} onDone={accept} />}

    <h2>{c.detail.history}</h2>
    <ul className="donation-plain">{gift.history.map((entry, i) => <li key={i}>{date(entry.at)} · {c.history[entry.action]}{entry.by ? ` · ${entry.by.displayName}` : ''}</li>)}</ul>
  </section>;
}

type FormProps = { c: Copy; gift: DonationDetail; onDone: (gift: DonationDetail) => void };

function Evidence({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const { busy, error, run } = useAction();
  return <div className="donation-evidence-view">
    <p className="donation-note">{c.detail.evidenceWarning}</p>
    {/* Always from the authenticated route, never from a stored copy (donation-lifecycle 16). */}
    <img src={donationAdmin.evidenceUrl(gift.id)} alt={c.detail.evidenceAlt} />
    <Problem c={c} error={error} />
    <button type="button" className="donation-secondary" disabled={busy} onClick={async () => { const next = await run(token => donationAdmin.deleteEvidence(gift.id, token, locale)); if (next) onDone(next); }}>{c.detail.deleteEvidence}</button>
  </div>;
}

function ConfirmForm({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const { busy, error, run } = useAction(); const key = useRef(newIdempotencyKey());
  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const next = await run(token => donationAdmin.confirm(gift.id, {
      caseCode: text(data, 'caseCode').toUpperCase(), confirmedAmount: cents(text(data, 'confirmedAmount')), receivedOn: text(data, 'receivedOn'),
      listing: text(data, 'listing') as 'as_requested' | 'anonymous' | 'hidden', ...optional(data, 'providerReference'), ...optional(data, 'note'),
    }, key.current, token, locale));
    if (next) onDone(next);
  }
  return <form className="donation-action" onSubmit={onSubmit}><fieldset disabled={busy}><legend>{c.confirm.title}</legend>
    <p className="donation-keep">{c.confirm.duty}</p>
    <Field name="caseCode" label={c.confirm.caseCode} required />
    <Field name="confirmedAmount" label={c.confirm.amount} required />
    <Field name="receivedOn" label={c.confirm.receivedOn} type="date" required max={today()} />
    <Field name="providerReference" label={c.confirm.providerReference} />
    <Choice name="listing" label={c.confirm.listing} options={[['as_requested', c.confirm.as_requested], ['anonymous', c.confirm.anonymous], ['hidden', c.confirm.hidden]]} />
    <Field name="note" label={c.confirm.note} hint={c.confirm.noteHint} />
    <Problem c={c} error={error} />
    <button className="identity-primary" type="submit">{busy ? c.busy : c.confirm.submit}</button>
  </fieldset></form>;
}

function RejectForm({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const { busy, error, run } = useAction();
  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const next = await run(token => donationAdmin.reject(gift.id, { reason: text(data, 'reason') as 'not_received' | 'duplicate' | 'other', ...(text(data, 'rejectNote') ? { note: text(data, 'rejectNote') } : {}) }, token, locale));
    if (next) onDone(next);
  }
  return <form className="donation-action" onSubmit={onSubmit}><fieldset disabled={busy}><legend>{c.reject.title}</legend>
    <Choice name="reason" label={c.reject.reason} options={[['not_received', c.reasons.not_received], ['duplicate', c.reasons.duplicate], ['other', c.reasons.other]]} />
    <Field name="rejectNote" label={c.reject.note} hint={c.reject.noteHint} />
    <Problem c={c} error={error} />
    <button className="donation-secondary" type="submit">{busy ? c.busy : c.reject.submit}</button>
  </fieldset></form>;
}

function ReopenForm({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const { busy, error, run } = useAction();
  return <div className="donation-action"><h2>{c.reopen.title}</h2><p>{c.reopen.body}</p><Problem c={c} error={error} />
    <button className="identity-primary" type="button" disabled={busy} onClick={async () => { const next = await run(token => donationAdmin.reopen(gift.id, token, locale)); if (next) onDone(next); }}>{busy ? c.busy : c.reopen.submit}</button></div>;
}

/** The question comes first: did money go back? Two forms with different required fields (8.1). */
function CorrectionForms({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const [kind, setKind] = useState<'reversal' | 'refund' | null>(null);
  const reversal = useAction(); const refund = useAction(); const key = useRef(newIdempotencyKey());
  async function onReversal(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const next = await reversal.run(token => donationAdmin.reverse(gift.id, { amount: cents(text(data, 'reversalAmount')), reason: text(data, 'reversalReason') as 'duplicate_confirmation' | 'wrong_amount' | 'wrong_case' | 'not_received' | 'other', note: text(data, 'reversalNote') }, key.current, token, locale));
    if (next) onDone(next);
  }
  async function onRefund(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const reference = text(data, 'refundReference');
    const next = await refund.run(token => donationAdmin.refund(gift.id, { amount: cents(text(data, 'refundAmount')), refundedOn: text(data, 'refundedOn'), note: text(data, 'refundNote'), ...(reference ? { providerReference: reference } : {}) }, key.current, token, locale));
    if (next) onDone(next);
  }
  return <div className="donation-action"><fieldset className="donation-display"><legend>{c.correct.question}</legend>
      <label className="donation-method"><input type="radio" name="correction" checked={kind === 'reversal'} onChange={() => { setKind('reversal'); key.current = newIdempotencyKey(); }} /><span>{c.correct.reversalTitle}</span></label>
      <label className="donation-method"><input type="radio" name="correction" checked={kind === 'refund'} onChange={() => { setKind('refund'); key.current = newIdempotencyKey(); }} /><span>{c.correct.refundTitle}</span></label>
    </fieldset>
    {kind === 'reversal' && <form onSubmit={onReversal}><fieldset disabled={reversal.busy}><p>{c.correct.reversalBody}</p>
      <Field name="reversalAmount" label={c.correct.amount} required />
      <Choice name="reversalReason" label={c.correct.reason} options={[['duplicate_confirmation', c.reasons.duplicate_confirmation], ['wrong_amount', c.reasons.wrong_amount], ['wrong_case', c.reasons.wrong_case], ['not_received', c.reasons.not_received], ['other', c.reasons.other]]} />
      <Field name="reversalNote" label={c.correct.note} required />
      <Problem c={c} error={reversal.error} />
      <button className="identity-primary" type="submit">{reversal.busy ? c.busy : c.correct.submitReversal}</button></fieldset></form>}
    {kind === 'refund' && <form onSubmit={onRefund}><fieldset disabled={refund.busy}><p>{c.correct.refundBody}</p>
      <Field name="refundAmount" label={c.correct.amount} required />
      <Field name="refundedOn" label={c.correct.refundedOn} type="date" required max={today()} />
      <Field name="refundReference" label={c.correct.providerReference} />
      <Field name="refundNote" label={c.correct.note} required />
      <Problem c={c} error={refund.error} />
      <button className="identity-primary" type="submit">{refund.busy ? c.busy : c.correct.submitRefund}</button></fieldset></form>}
  </div>;
}

function ListingForm({ c, gift, onDone }: FormProps) {
  const { locale } = useI18n(); const { busy, error, run } = useAction();
  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const listing = text(new FormData(event.currentTarget), 'restrict') as 'anonymous' | 'hidden';
    const next = await run(token => donationAdmin.restrictListing(gift.id, { listing }, token, locale)); if (next) onDone(next);
  }
  const options: Array<[string, string]> = gift.display.mode === 'named' ? [['anonymous', c.listing.anonymous], ['hidden', c.listing.hidden]] : [['hidden', c.listing.hidden]];
  return <form className="donation-action" onSubmit={onSubmit}><fieldset disabled={busy}><legend>{c.listing.title}</legend><p>{c.listing.body}</p>
    <Choice name="restrict" label={c.listing.title} options={options} />
    <Problem c={c} error={error} />
    <button className="donation-secondary" type="submit">{busy ? c.busy : c.listing.submit}</button></fieldset></form>;
}

// ---------------------------------------------------------------- manual entry

export function ManualEntryPage() { return <Staff>{() => <ManualEntry />}</Staff>; }

function ManualEntry() {
  const { locale } = useI18n(); const c = adminCopy(locale); const navigate = useNavigate();
  const { busy, error, run } = useAction(); const key = useRef(newIdempotencyKey());
  const [mode, setMode] = useState<'named' | 'anonymous' | 'hidden'>('anonymous');
  useEffect(() => { document.title = c.entry.title; }, [c]);
  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const methodId = text(data, 'methodId'); const description = text(data, 'methodDescription');
    const created = await run(token => donationAdmin.enter({
      caseCode: text(data, 'caseCode').toUpperCase(), amount: cents(text(data, 'amount')), receivedOn: text(data, 'receivedOn'),
      method: methodId ? { id: methodId } : { description }, payerName: text(data, 'payerName'),
      display: mode === 'named' ? { mode, publicName: text(data, 'publicName') } : { mode },
      note: text(data, 'note'), ...optional(data, 'providerReference'), ...optional(data, 'reentryOf'),
    }, key.current, token, locale));
    if (created) navigate(`/admin/donations/${encodeURIComponent(created.id)}`);
  }
  return <section className="donation-admin-panel">
    <p><Link to="/admin/donations">{c.nav.queue}</Link></p>
    <h1>{c.entry.title}</h1><p className="donation-keep">{c.entry.intro}</p>
    <form className="donation-action" onSubmit={onSubmit}><fieldset disabled={busy}>
      <Field name="caseCode" label={c.entry.caseCode} required />
      <Field name="amount" label={c.entry.amount} required />
      <Field name="receivedOn" label={c.entry.receivedOn} type="date" required max={today()} />
      <Field name="methodId" label={c.entry.methodId} hint={c.entry.methodHint} />
      <Field name="methodDescription" label={c.entry.methodDescription} />
      <Field name="payerName" label={c.entry.payerName} required />
      <Field name="providerReference" label={c.entry.providerReference} hint={c.entry.providerReferenceHint} />
      <fieldset className="donation-display"><legend>{c.entry.display}</legend>
        {(['named', 'anonymous', 'hidden'] as const).map(m => <label key={m} className="donation-method"><input type="radio" name="display" checked={mode === m} onChange={() => setMode(m)} /><span>{c.display[m]}</span></label>)}
        {mode === 'named' && <Field name="publicName" label={c.entry.publicName} required />}
      </fieldset>
      <Field name="note" label={c.entry.note} required />
      <Field name="reentryOf" label={c.entry.reentryOf} />
      <Problem c={c} error={error} />
      <button className="identity-primary" type="submit">{busy ? c.busy : c.entry.submit}</button>
    </fieldset></form>
  </section>;
}
