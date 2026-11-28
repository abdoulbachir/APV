-- APV-GAKASINAY, milestone 2B: what the donation services need beside migration V6.
--
--   A. Corrections. The ledger holds the entry of a reversal or a refund; what the reviewer
--      stated with it (the reason, the day the money left, the provider's reference for the
--      return, a private note) is recorded here, one row per correction entry
--      (contract/donation-lifecycle.md, 8.1 and 8.2).
--   B. The code of the confirmed case is kept beside its identifier. A case code never
--      changes (aid_case_guard) and a confirmation is never undone, so the copy cannot go
--      stale, and the donations module never reads the cases table.
--   C. A payment method records when it was deactivated: a report naming its details is still
--      accepted for 24 hours (contract 2.4).
--   D. Retention erases private notes (contract 14.1), including the note of a staff entry,
--      which that origin otherwise requires.
--   E. The general fund is never deleted (contract 10).

-- ---------------------------------------------------------------------------------------
-- A. Corrections
-- ---------------------------------------------------------------------------------------

create table donation_correction (
    id                      bigint generated always as identity primary key,
    donation_id             bigint not null references donation (id),
    ledger_entry_id         bigint not null unique references ledger_entry (id),
    kind                    text not null check (kind in ('donation_reversal', 'refund')),
    amount_cents            bigint not null check (amount_cents between 1 and 10000000000),
    reason                  text check (reason in ('duplicate_confirmation', 'wrong_amount', 'wrong_case', 'not_received', 'other')),
    refunded_on             date,
    provider_reference      text,
    -- Private. Erased by retention with the gift's other private details.
    note                    text,
    recorded_by_account_id  bigint not null references account (id),
    recorded_at             timestamptz not null check (recorded_at = date_trunc('second', recorded_at)),
    -- The two kinds state different things and can never be mistaken for each other (8.1).
    constraint donation_correction_shape check (
        (kind = 'donation_reversal' and reason is not null and refunded_on is null and provider_reference is null)
        or (kind = 'refund' and reason is null and refunded_on is not null))
);

create index donation_correction_by_gift on donation_correction (donation_id, recorded_at, id);
-- The provider's reference for a return is recorded at most once against one gift (8.2).
create unique index donation_correction_provider_reference on donation_correction (donation_id, provider_reference)
    where provider_reference is not null;

comment on table donation_correction is 'What was stated with a reversal or a refund. The amounts are those of the ledger entry; nothing here is public.';

-- A correction cannot be corrected or removed (8.2). The one change is retention erasing its note.
create function donation_correction_is_guarded() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a correction is never deleted' using errcode = 'restrict_violation';
    end if;
    if new.note is not null
       or old.donation_id <> new.donation_id or old.ledger_entry_id <> new.ledger_entry_id or old.kind <> new.kind
       or old.amount_cents <> new.amount_cents or old.reason is distinct from new.reason
       or old.refunded_on is distinct from new.refunded_on or old.provider_reference is distinct from new.provider_reference
       or old.recorded_by_account_id <> new.recorded_by_account_id or old.recorded_at <> new.recorded_at then
        raise exception 'a correction never changes; only its note can be erased' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger donation_correction_guard before update or delete on donation_correction
    for each row execute function donation_correction_is_guarded();

create trigger donation_correction_no_truncate before truncate on donation_correction
    for each statement execute function truncate_only_on_a_disposable_database();

-- ---------------------------------------------------------------------------------------
-- B. The code of the confirmed case
-- ---------------------------------------------------------------------------------------

alter table donation
    add column confirmed_case_code text collate "C" check (confirmed_case_code ~ '^[A-Z]{2,8}(-[0-9]{1,6})?$'),
    add constraint donation_confirmed_case_together check ((confirmed_case_id is null) = (confirmed_case_code is null));

-- ---------------------------------------------------------------------------------------
-- C. When a payment method was deactivated
-- ---------------------------------------------------------------------------------------

alter table payment_method
    add column deactivated_at timestamptz,
    add constraint payment_method_active_shape check (not active or deactivated_at is null);

-- ---------------------------------------------------------------------------------------
-- D. Retention may erase the note of a staff entry
-- ---------------------------------------------------------------------------------------

alter table donation drop constraint donation_staff_origin;
alter table donation add constraint donation_staff_origin check (
    origin <> 'staff_entry'
    or (reported_case_code is null and reported_amount_cents is null and paid_on is null
        and reported_at is null and state = 'confirmed'
        and (note is not null or private_details_erased_at is not null)));

-- ---------------------------------------------------------------------------------------
-- E. The general fund is never removed (contract 10). Its code and kind already never change
--    (aid_case_guard); this adds the row itself. TRUNCATE on a disposable database, which is
--    how the fixture loader replaces every case, is a different statement and is unaffected.
-- ---------------------------------------------------------------------------------------

create function aid_case_fund_is_never_deleted() returns trigger language plpgsql as $$
begin
    raise exception 'the general fund is never deleted' using errcode = 'restrict_violation';
end $$;

create trigger aid_case_fund_never_deleted before delete on aid_case
    for each row when (old.kind = 'fund') execute function aid_case_fund_is_never_deleted();
