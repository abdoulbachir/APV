-- APV-GAKASINAY, milestone 2B: the donation lifecycle (contract/donation-lifecycle.md).
-- Reporting a gift, private evidence, review, manual entry, corrections, payment methods,
-- and the general fund on a real database. Module ownership: everything here belongs to the
-- donations module, except the general-fund row, which belongs to cases, and the ledger entry
-- a confirmation writes, which belongs to ledger. The donations module reaches those through
-- their modules, never by touching their tables directly.

-- ---------------------------------------------------------------------------------------
-- Payment methods (contract section 2). Records, not code: a new method that uses the same
-- pay-outside-then-report flow needs no migration. Recipient details are versioned; a gift
-- keeps the version that was shown when it was reported.
-- ---------------------------------------------------------------------------------------

create table payment_method (
    id                     text collate "C" primary key check (id ~ '^[a-z][a-z0-9_]{1,31}$'),
    name_en                text not null check (name_en <> ''),
    name_fr                text not null check (name_fr <> ''),
    position               integer not null check (position >= 1),
    active                 boolean not null default false,
    -- Eligibility attestation (contract 2.5). Recorded by a person; production refuses to
    -- activate a method without it. Not proof, an attestation.
    eligibility_checked_by text,
    eligibility_checked_on date,
    eligibility_notes      text,
    created_at             timestamptz not null default now(),
    constraint payment_method_position_unique unique (position) deferrable initially deferred
);

comment on table payment_method is 'A payment method the super administrator manages, like a category. The identifier is immutable and never reused; a method shown to a donor is never deleted, only deactivated.';

-- The identifier never changes.
create function payment_method_id_is_immutable() returns trigger language plpgsql as $$
begin
    if old.id <> new.id then
        raise exception 'a payment method identifier never changes' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger payment_method_id_immutable before update of id on payment_method
    for each row execute function payment_method_id_is_immutable();

create table payment_method_details (
    id               bigint generated always as identity primary key,
    public_id        text collate "C" not null unique check (public_id ~ '^pmd_[A-Za-z0-9]{22}$'),
    method_id        text collate "C" not null references payment_method (id),
    revision         integer not null check (revision >= 1),
    instructions_en  text not null check (instructions_en <> ''),
    instructions_fr  text not null check (instructions_fr <> ''),
    -- One to six fields: [{ "labels": { "en": .., "fr": .. }, "value": .., "format": "text"|"link" }].
    recipient        jsonb not null,
    published_at     timestamptz not null default now(),
    superseded_at    timestamptz,
    constraint payment_method_details_revision_unique unique (method_id, revision),
    constraint payment_method_details_recipient_shape check (
        jsonb_typeof(recipient) = 'array'
        and jsonb_array_length(recipient) between 1 and 6)
);

comment on column payment_method_details.recipient is 'The recipient fields shown to the donor. Public by nature: a donor must read them before paying. Never rendered as HTML.';

-- At most one current (not superseded) version per method.
create unique index payment_method_details_current on payment_method_details (method_id) where superseded_at is null;

-- Append-only apart from superseding once: nothing in a published version changes, and
-- superseded_at goes from null to a value exactly once.
create function payment_method_details_is_append_only() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a details version is never deleted' using errcode = 'restrict_violation';
    end if;
    if old.superseded_at is not null then
        raise exception 'a superseded details version never changes again' using errcode = 'check_violation';
    end if;
    if old.public_id <> new.public_id or old.method_id <> new.method_id or old.revision <> new.revision
       or old.instructions_en <> new.instructions_en or old.instructions_fr <> new.instructions_fr
       or old.recipient <> new.recipient or old.published_at <> new.published_at then
        raise exception 'only superseded_at may be set on a details version' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger payment_method_details_append_only before update or delete on payment_method_details
    for each row execute function payment_method_details_is_append_only();

-- ---------------------------------------------------------------------------------------
-- Gifts (contract section 1). One row from the moment a gift is reported or entered until
-- for ever; never deleted. Reported and confirmed values are separate columns.
-- ---------------------------------------------------------------------------------------

create table donation (
    id                        bigint generated always as identity primary key,
    public_id                 text collate "C" not null unique check (public_id ~ '^don_[A-Za-z0-9]{22}$'),
    -- The reference a donor quotes. The first eight characters of the tracking code.
    reference                 text collate "C" not null unique check (reference ~ '^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$'),
    -- Random, held only here. The tracking code tail and the evidence upload token are derived
    -- from this and a key that is not in the database (HMAC). A database copy alone yields neither.
    secret                    bytea not null check (length(secret) = 32),
    origin                    text not null check (origin in ('reported', 'staff_entry')),
    state                     text not null check (state in ('reported', 'confirmed', 'rejected')),

    -- Who, when fully signed in at report time. Never set by matching an address or a name.
    account_id                bigint references account (id),

    -- What the donor reported. Null for a staff entry: nobody reported it.
    reported_case_code        text collate "C" check (reported_case_code ~ '^[A-Z]{2,8}(-[0-9]{1,6})?$'),
    reported_amount_cents     bigint check (reported_amount_cents between 1 and 10000000000),
    paid_on                   date,
    reported_at               timestamptz,

    -- Private supporting details. Erased by retention (contract 14); never public, never logged.
    payer_name                text,
    payment_reference         text,
    contact                   text,

    -- The method and the exact details version shown. Null method with a description for a
    -- staff entry of a way that is not a record (cash).
    payment_method_id         text collate "C" references payment_method (id),
    payment_details_id        bigint references payment_method_details (id),
    method_description        text,

    -- How the donor asked to appear, and the effective public listing once confirmed.
    display_mode              text not null check (display_mode in ('named', 'anonymous', 'hidden')),
    public_name               text,
    listing                   text check (listing in ('named', 'anonymous', 'hidden')),

    -- The decision.
    decided_at                timestamptz,
    decided_by_account_id     bigint references account (id),
    confirmed_case_id         bigint references aid_case (id),
    confirmed_amount_cents    bigint check (confirmed_amount_cents between 1 and 10000000000),
    received_on               date,
    provider_reference        text,
    rejection_reason          text check (rejection_reason in ('not_received', 'duplicate', 'other')),
    note                      text,

    -- The ledger entry a confirmation writes, and the re-entry link (contract 8.3).
    ledger_entry_id           bigint unique references ledger_entry (id),
    reentry_of                bigint unique references donation (id),

    private_details_erased_at timestamptz,
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now(),

    -- State shape: what each state requires and forbids.
    constraint donation_reported_shape check (
        state <> 'reported'
        or (ledger_entry_id is null and confirmed_case_id is null and confirmed_amount_cents is null
            and decided_at is null and rejection_reason is null and listing is null)),
    constraint donation_confirmed_shape check (
        state <> 'confirmed'
        or (ledger_entry_id is not null and confirmed_case_id is not null and confirmed_amount_cents is not null
            and received_on is not null and decided_at is not null and decided_by_account_id is not null
            and listing is not null and rejection_reason is null)),
    constraint donation_rejected_shape check (
        state <> 'rejected'
        or (ledger_entry_id is null and rejection_reason is not null
            and decided_at is not null and decided_by_account_id is not null and listing is null)),
    -- Origin shape: a report carries reported values; a staff entry carries none and is confirmed.
    constraint donation_reported_origin check (
        origin <> 'reported'
        or (reported_case_code is not null and reported_amount_cents is not null
            and paid_on is not null and reported_at is not null)),
    constraint donation_staff_origin check (
        origin <> 'staff_entry'
        or (reported_case_code is null and reported_amount_cents is null and paid_on is null
            and reported_at is null and state = 'confirmed' and note is not null)),
    -- A method is either a record or a free-form description, never both, never neither.
    constraint donation_method_shape check ((payment_method_id is not null) <> (method_description is not null)),
    -- A named listing needs a public name; hidden and anonymous never carry one.
    constraint donation_listing_name check (
        listing is null
        or (listing = 'named') = (public_name is not null)),
    constraint donation_whole_seconds check (
        (reported_at is null or reported_at = date_trunc('second', reported_at))
        and (decided_at is null or decided_at = date_trunc('second', decided_at)))
);

create index donation_queue on donation (reported_at, id) where state = 'reported';
create index donation_by_state on donation (state, created_at desc, id);
create index donation_by_reported_case on donation (reported_case_code) where reported_case_code is not null;
create index donation_by_confirmed_case on donation (confirmed_case_id) where confirmed_case_id is not null;
create index donation_by_account on donation (account_id) where account_id is not null;
-- A provider reference is recorded at most once per payment method (contract 6.3).
create unique index donation_provider_reference_unique on donation (payment_method_id, provider_reference)
    where provider_reference is not null and payment_method_id is not null and state = 'confirmed';
-- Possible-duplicate hints match on method, amount and payer name within a window (contract 6.2).
create index donation_duplicate_hint on donation (payment_method_id, confirmed_amount_cents) where payer_name is not null;

comment on column donation.reference is 'The first eight characters of the tracking code, shown to the donor and to staff. Not secret.';
comment on column donation.listing is 'The effective public listing once confirmed. display_mode is the donor''s reported choice; listing can only ever show less than that (contract 8.4).';
comment on column donation.public_name is 'A name the donor agreed to show. Kept for ever (contract 14.1); never a private detail.';

-- A gift is never deleted, and its reported values never change once written.
create function donation_is_guarded() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'a gift is never deleted' using errcode = 'restrict_violation';
    end if;
    if old.public_id <> new.public_id or old.reference <> new.reference or old.origin <> new.origin
       or old.created_at <> new.created_at then
        raise exception 'the identity of a gift never changes' using errcode = 'check_violation';
    end if;
    if old.state = 'confirmed' and new.state <> 'confirmed' then
        raise exception 'a confirmed gift is never unconfirmed; mistakes are ledger corrections' using errcode = 'check_violation';
    end if;
    if old.reported_at is not null and (old.reported_at <> new.reported_at
        or old.reported_amount_cents is distinct from new.reported_amount_cents
        or old.reported_case_code is distinct from new.reported_case_code) then
        raise exception 'the reported values of a gift never change' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger donation_guard before update or delete on donation
    for each row execute function donation_is_guarded();

-- ---------------------------------------------------------------------------------------
-- Private evidence (contract section 4). One re-encoded screenshot per gift, never public.
-- ---------------------------------------------------------------------------------------

create table donation_evidence (
    donation_id  bigint primary key references donation (id),
    storage_key  text not null unique check (storage_key !~ '(^/|\.\.|\\)'),
    format       text not null check (format in ('jpeg', 'png')),
    width        integer not null check (width between 1 and 100000),
    height       integer not null check (height between 1 and 100000),
    bytes        bigint not null check (bytes >= 0),
    sha256       bytea not null check (length(sha256) = 32),
    uploaded_at  timestamptz not null default now(),
    deleted_at   timestamptz
);

comment on column donation_evidence.storage_key is 'Key inside the private storage area. Random, never derived from the gift, the donor or the file. Never an absolute path, never sent to a client.';

-- The stored file is produced by the server and its row is append-only apart from deletion.
create function donation_evidence_is_guarded() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'evidence rows are kept as a record even after the file is deleted' using errcode = 'restrict_violation';
    end if;
    return new;
end $$;

create trigger donation_evidence_guard before delete on donation_evidence
    for each row execute function donation_evidence_is_guarded();

-- ---------------------------------------------------------------------------------------
-- The short per-gift history the review screen shows (contract 13). Append-only.
-- ---------------------------------------------------------------------------------------

create table donation_history (
    id                bigint generated always as identity primary key,
    donation_id       bigint not null references donation (id),
    at                timestamptz not null default now(),
    action            text not null check (action in (
                          'reported', 'evidence_attached', 'evidence_deleted', 'confirmed', 'rejected',
                          'reopened', 'entered', 'reversed', 'refunded', 'listing_restricted')),
    actor_account_id  bigint references account (id)
);

create index donation_history_by_gift on donation_history (donation_id, at, id);

create function donation_history_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'the gift history is append-only: % is not allowed', tg_op using errcode = 'restrict_violation';
end $$;

create trigger donation_history_append_only before update or delete on donation_history
    for each row execute function donation_history_is_append_only();

-- ---------------------------------------------------------------------------------------
-- Idempotency (contract 3.6, 6.6). A key scoped to the caller and the operation. The same key
-- with the same request returns the first answer; with a different request it is refused.
-- ---------------------------------------------------------------------------------------

create table idempotency_record (
    id            bigint generated always as identity primary key,
    scope         text not null,
    operation     text not null,
    key_hash      bytea not null check (length(key_hash) = 32),
    request_hash  bytea not null check (length(request_hash) = 32),
    status_code   integer not null,
    response_json text not null,
    created_at    timestamptz not null default now(),
    constraint idempotency_record_unique unique (scope, operation, key_hash)
);

create index idempotency_record_by_age on idempotency_record (created_at);

comment on table idempotency_record is 'One row per (caller scope, operation, key). Old rows are deleted by a scheduled task. The key itself is stored only as a keyed hash.';

-- ---------------------------------------------------------------------------------------
-- Rate-limit counters for the donation operations (contract 12). Same shape and atomic
-- increment as the identity throttle; owned by this module, keyed by a keyed hash.
-- ---------------------------------------------------------------------------------------

create table donation_throttle (
    scope         text not null,
    key_hash      bytea not null,
    window_start  timestamptz not null,
    attempts      integer not null check (attempts >= 0),
    constraint donation_throttle_pk primary key (scope, key_hash, window_start)
);

create index donation_throttle_by_window on donation_throttle (window_start);

-- ---------------------------------------------------------------------------------------
-- The general fund on a real database (contract section 10). Inserted once, empty, when no
-- fund exists. A migration runs once per database, on every database, including one that
-- already holds accounts. On a disposable database loaded from a fixture a fund already
-- exists, so nothing is inserted. The approved English and French text ships as written.
-- ---------------------------------------------------------------------------------------

insert into aid_case (code, kind, status, accepts_donations, public_name, title_en, title_fr, story_en, story_fr, published_at)
select 'FUND', 'fund', 'active', true, '', 'General fund', 'Fonds général',
       'Gifts to the general fund are not tied to one case. They are kept until APV-GAKASINAY allocates them to a case: for an urgent need, or to complete a case that has not reached its goal. Every allocation appears in the record of this fund and in the record of the case that receives it. The fund never pays a beneficiary directly.',
       'Les dons au fonds général ne sont pas liés à un cas précis. Ils sont conservés jusqu’à ce qu’APV-GAKASINAY les affecte à un cas : pour un besoin urgent, ou pour compléter un cas qui n’a pas atteint son objectif. Chaque affectation apparaît dans l’historique de ce fonds et dans celui du cas qui la reçoit. Le fonds ne verse jamais d’argent directement à un bénéficiaire.',
       date_trunc('second', now())
where not exists (select 1 from aid_case where kind = 'fund');

-- ---------------------------------------------------------------------------------------
-- Truncate protection (migration V5 pattern). The financial and evidence tables are never
-- truncated on a real database; the transient counters (throttle, idempotency) are not
-- protected, like auth_throttle.
-- ---------------------------------------------------------------------------------------

create trigger payment_method_no_truncate before truncate on payment_method
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger payment_method_details_no_truncate before truncate on payment_method_details
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger donation_no_truncate before truncate on donation
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger donation_evidence_no_truncate before truncate on donation_evidence
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger donation_history_no_truncate before truncate on donation_history
    for each statement execute function truncate_only_on_a_disposable_database();
