-- APV-GAKASINAY, milestone 0B: schema of the public read model.
--
-- Conventions
--   * Money is bigint cents. One movement is at most 10^10; totals are summed as numeric.
--   * Instants are timestamptz at whole-second precision, stored in UTC.
--   * Public identifiers and codes are ASCII and use COLLATE "C", so ordering never depends
--     on a locale.
--   * "aid_case" is the table of cases; "case" is a reserved word in SQL.
--
-- Constraints here protect the data whatever the application does. Controls that need a
-- signed-in actor (who may confirm, who may disburse, idempotency keys, audit records) are
-- added with the write endpoints in later milestones.

-- ---------------------------------------------------------------------------------------
-- Organization settings (module: site)
-- ---------------------------------------------------------------------------------------

create table organization_settings (
    singleton       boolean primary key default true check (singleton),
    timezone        text not null,
    default_locale  text not null default 'en' check (default_locale in ('en', 'fr'))
);

comment on table organization_settings is
    'One row. timezone is the IANA zone used for NEW deadlines only; existing cases keep the zone and cutoff stored on them.';

insert into organization_settings (timezone) values ('America/Chicago');

-- ---------------------------------------------------------------------------------------
-- Categories (module: cases). Rows, never an enumeration in code.
-- ---------------------------------------------------------------------------------------

create table category (
    id        text collate "C" primary key check (id ~ '^[a-z][a-z0-9-]{1,31}$'),
    label_en  text not null check (char_length(label_en) between 1 and 40),
    label_fr  text not null check (char_length(label_fr) between 1 and 40),
    position  integer not null check (position >= 1),
    active    boolean not null default true,
    constraint category_position_unique unique (position) deferrable initially deferred
);

comment on column category.id is 'Stable identifier. Never updated, never reused. A category in use is deactivated, not deleted.';

insert into category (id, label_en, label_fr, position) values
    ('education', 'Education', 'Éducation', 1),
    ('medical',   'Medical',   'Santé',     2),
    ('housing',   'Housing',   'Logement',  3),
    ('family',    'Family',    'Famille',   4),
    ('emergency', 'Emergency', 'Urgence',   5),
    ('other',     'Other',     'Autre',     6);

create function category_id_is_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'category identifiers never change (% -> %)', old.id, new.id using errcode = 'check_violation';
end $$;

create trigger category_id_immutable before update of id on category
    for each row when (old.id is distinct from new.id) execute function category_id_is_immutable();

-- ---------------------------------------------------------------------------------------
-- Media metadata (module: media). Upload and storage services come in a later milestone.
-- ---------------------------------------------------------------------------------------

create table media_asset (
    id           bigint generated always as identity primary key,
    token        text collate "C" not null unique check (token ~ '^[A-Za-z0-9_-]{22,64}$'),
    kind         text not null check (kind in ('case_cover', 'case_gallery', 'update_photo', 'avatar')),
    format       text not null check (format in ('webp', 'jpg', 'png', 'avif')),
    storage_key  text not null check (storage_key !~ '(^/|\.\.|\\)'),
    published    boolean not null default false
);

comment on column media_asset.token is 'Random, unguessable. Retired with the row; never reused.';
comment on column media_asset.storage_key is 'Key inside the storage root. Never an absolute path, never sent to a client.';
comment on column media_asset.published is 'False for draft, withdrawn or not-consented pictures. Only published rows are visible through public_media.';

-- The only way other modules read media: unpublished pictures simply do not exist here.
create view public_media as
    select id, token, format from media_asset where published;

-- ---------------------------------------------------------------------------------------
-- Cases and the general fund (module: cases)
-- ---------------------------------------------------------------------------------------

create table aid_case (
    id                 bigint generated always as identity primary key,
    code               text collate "C" not null unique check (code ~ '^[A-Z]{2,8}(-[0-9]{1,6})?$'),
    kind               text not null check (kind in ('case', 'fund')),
    status             text not null check (status in ('draft', 'active', 'funded', 'delivered', 'closed')),
    accepts_donations  boolean not null default true,
    category_id        text collate "C" references category (id),
    featured_rank      integer check (featured_rank >= 1),
    public_name        text not null default '',
    title_en           text check (title_en <> ''),
    title_fr           text check (title_fr <> ''),
    story_en           text check (story_en <> ''),
    story_fr           text check (story_fr <> ''),
    goal_cents         bigint check (goal_cents between 1 and 10000000000),
    deadline_date      date,
    deadline_timezone  text,
    deadline_ends_at   timestamptz,
    published_at       timestamptz,
    cover_media_id     bigint references media_asset (id),
    cover_alt_en       text check (cover_alt_en <> ''),
    cover_alt_fr       text check (cover_alt_fr <> ''),
    -- Search text, normalized by the application with the exact algorithm of the contract.
    search_title_en    text not null default '',
    search_title_fr    text not null default '',
    search_public_name text not null default '',
    search_code        text not null default '',

    constraint aid_case_category_matches_kind check ((kind = 'fund') = (category_id is null)),
    constraint aid_case_fund_shape check (
        kind <> 'fund' or (code = 'FUND' and status <> 'draft' and goal_cents is null
                           and deadline_date is null and featured_rank is null)),
    constraint aid_case_deadline_complete check (
        (deadline_date is null) = (deadline_timezone is null)
        and (deadline_date is null) = (deadline_ends_at is null)),
    -- A case cannot be published without both languages (approved owner decision).
    constraint aid_case_published_is_complete check (
        status = 'draft' or (published_at is not null and title_en is not null and title_fr is not null
                             and story_en is not null and story_fr is not null)),
    constraint aid_case_cover_has_alt check (cover_media_id is null or cover_alt_en is not null or cover_alt_fr is not null),
    constraint aid_case_whole_seconds check (
        (published_at is null or published_at = date_trunc('second', published_at))
        and (deadline_ends_at is null or deadline_ends_at = date_trunc('second', deadline_ends_at)))
);

create unique index aid_case_single_fund on aid_case (kind) where kind = 'fund';
create unique index aid_case_featured_rank_unique on aid_case (featured_rank) where featured_rank is not null;
create index aid_case_directory on aid_case (status, published_at desc, code) where kind = 'case';

comment on column aid_case.deadline_timezone is 'The organization timezone at the moment the deadline was set. Stored so a later change of the setting cannot move this deadline.';
comment on column aid_case.deadline_ends_at is 'Cutoff: first instant of the day after deadline_date in deadline_timezone, computed with that zone''s daylight saving rules.';
comment on column aid_case.published_at is 'When the case was first published. Returned as createdAt.';

-- A published case never becomes a draft again (contract/financial-terms.md, section 7).
create function aid_case_guard() returns trigger language plpgsql as $$
begin
    if old.status <> 'draft' and new.status = 'draft' then
        raise exception 'case % was published and cannot return to draft', old.code using errcode = 'check_violation';
    end if;
    if old.code <> new.code or old.kind <> new.kind then
        raise exception 'the code and kind of case % never change', old.code using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger aid_case_guard before update on aid_case for each row execute function aid_case_guard();

-- Private beneficiary details live in their own table, which no public query reads.
create table case_private (
    case_id    bigint primary key references aid_case (id),
    real_name  text not null default '',
    contact    text not null default '',
    notes      text not null default ''
);

create table case_photo (
    id         bigint generated always as identity primary key,
    case_id    bigint not null references aid_case (id),
    public_id  text collate "C" not null,
    media_id   bigint not null references media_asset (id),
    alt_en     text check (alt_en <> ''),
    alt_fr     text check (alt_fr <> ''),
    position   integer not null check (position between 1 and 8),
    constraint case_photo_position_unique unique (case_id, position),
    constraint case_photo_public_id_unique unique (case_id, public_id),
    constraint case_photo_has_alt check (alt_en is not null or alt_fr is not null)
);

create table case_update (
    id                     bigint generated always as identity primary key,
    public_id              text collate "C" not null unique,
    case_id                bigint not null references aid_case (id),
    text_en                text check (text_en <> ''),
    text_fr                text check (text_fr <> ''),
    posted_at              timestamptz not null check (posted_at = date_trunc('second', posted_at)),
    -- Becomes a reference to the administrator's account when accounts exist (milestone 1).
    author_name            text not null,
    author_avatar_media_id bigint references media_asset (id),
    author_avatar_consent  boolean not null default false,
    photo_media_id         bigint references media_asset (id),
    photo_alt_en           text check (photo_alt_en <> ''),
    photo_alt_fr           text check (photo_alt_fr <> ''),
    constraint case_update_has_text check (text_en is not null or text_fr is not null),
    constraint case_update_photo_has_alt check (photo_media_id is null or photo_alt_en is not null or photo_alt_fr is not null)
);

create index case_update_by_case on case_update (case_id, posted_at desc, public_id desc);

-- ---------------------------------------------------------------------------------------
-- Ledger (module: ledger). Append-only.
-- ---------------------------------------------------------------------------------------

create table ledger_kind (
    kind              text primary key,
    allocated_sign    smallint not null check (allocated_sign in (-1, 0, 1)),
    delivered_sign    smallint not null check (delivered_sign in (-1, 0, 1)),
    corrects_kind     text references ledger_kind (kind),
    paired_with       text,
    is_discretionary  boolean not null,
    label_en          text not null,
    label_fr          text not null
);

comment on table ledger_kind is 'The nine kinds of contract/financial-terms.md, with their effect. Labels are display text.';
comment on column ledger_kind.is_discretionary is 'True for movements that are choices and are refused when they would leave the case below zero.';

insert into ledger_kind (kind, allocated_sign, delivered_sign, corrects_kind, paired_with, is_discretionary, label_en, label_fr) values
    ('donation',               1,  0, null,           null,                    false, 'Confirmed gift',               'Don confirmé'),
    ('transfer_in',            1,  0, null,           'transfer_out',          false, 'Transfer received',            'Transfert reçu'),
    ('transfer_out',          -1,  0, null,           'transfer_in',           true,  'Transfer sent',                'Transfert envoyé'),
    ('disbursement',           0,  1, null,           null,                    true,  'Delivered to the beneficiary', 'Remis au bénéficiaire'),
    ('donation_reversal',     -1,  0, 'donation',     null,                    false, 'Gift entry corrected',         'Écriture de don corrigée'),
    ('refund',                -1,  0, 'donation',     null,                    false, 'Refund to donor',              'Remboursement au donateur'),
    ('transfer_in_reversal',  -1,  0, 'transfer_in',  'transfer_out_reversal', true,  'Received transfer cancelled',  'Transfert reçu annulé'),
    ('transfer_out_reversal',  1,  0, 'transfer_out', 'transfer_in_reversal',  false, 'Sent transfer cancelled',      'Transfert envoyé annulé'),
    ('disbursement_reversal',  0, -1, 'disbursement', null,                    false, 'Delivery entry corrected',     'Écriture de remise corrigée');

create table ledger_entry (
    id            bigint generated always as identity primary key,
    public_id     text collate "C" not null unique check (public_id ~ '^[A-Za-z0-9_-]{1,40}$'),
    case_id       bigint not null references aid_case (id),
    kind          text not null references ledger_kind (kind),
    amount_cents  bigint not null check (amount_cents between 1 and 10000000000),
    effective_at  timestamptz not null check (effective_at = date_trunc('second', effective_at)),
    corrects_id   bigint references ledger_entry (id),
    pair_id       text collate "C",
    recorded_at   timestamptz not null default now()
);

create index ledger_entry_by_case on ledger_entry (case_id, effective_at desc, public_id desc);
create index ledger_entry_corrections on ledger_entry (corrects_id) where corrects_id is not null;
create index ledger_entry_pairs on ledger_entry (pair_id) where pair_id is not null;

-- Entries are never edited or deleted.
create function ledger_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'the ledger is append-only: % is not allowed', tg_op using errcode = 'restrict_violation';
end $$;

create trigger ledger_entry_append_only before update or delete on ledger_entry
    for each row execute function ledger_is_append_only();

-- Rules checked for every new entry (contract/financial-terms.md, sections 2, 5, 6 and 7).
create function ledger_entry_check() returns trigger language plpgsql as $$
declare
    kind_rule       ledger_kind%rowtype;
    owner_status    text;
    owner_code      text;
    target          ledger_entry%rowtype;
    already         numeric;
    gross           numeric;
    available_after numeric;
begin
    select * into kind_rule from ledger_kind where kind = new.kind;

    -- Serializes movements on one case, and gives a stable view of its status.
    select status, code into owner_status, owner_code from aid_case where id = new.case_id for update;
    if owner_status = 'draft' then
        raise exception 'no money can move on draft case %', owner_code using errcode = 'check_violation';
    end if;

    if (kind_rule.paired_with is not null) <> (new.pair_id is not null) then
        raise exception 'pair_id belongs on transfer kinds and only there (entry %)', new.public_id using errcode = 'check_violation';
    end if;

    if kind_rule.corrects_kind is null then
        if new.corrects_id is not null then
            raise exception 'a % is an original movement and cannot correct another entry', new.kind using errcode = 'check_violation';
        end if;
    else
        if new.corrects_id is null then
            raise exception 'a % must name the entry it corrects', new.kind using errcode = 'check_violation';
        end if;
        -- The lock makes two simultaneous corrections of one entry wait for each other.
        select * into target from ledger_entry where id = new.corrects_id for update;
        if target.kind <> kind_rule.corrects_kind then
            raise exception 'a % can only correct a %, not a %', new.kind, kind_rule.corrects_kind, target.kind using errcode = 'check_violation';
        end if;
        if target.case_id <> new.case_id then
            raise exception 'a correction is recorded on the case of the entry it corrects' using errcode = 'check_violation';
        end if;
        if new.effective_at < target.effective_at then
            raise exception 'a correction cannot be dated before the entry it corrects' using errcode = 'check_violation';
        end if;
        select coalesce(sum(amount_cents), 0) into already from ledger_entry where corrects_id = new.corrects_id;
        if already + new.amount_cents > target.amount_cents then
            raise exception 'corrections of entry % would total %, more than its amount %',
                target.public_id, already + new.amount_cents, target.amount_cents using errcode = 'check_violation';
        end if;
    end if;

    if kind_rule.is_discretionary then
        select coalesce(sum(e.amount_cents * (k.allocated_sign - k.delivered_sign)), 0)
               + new.amount_cents * (kind_rule.allocated_sign - kind_rule.delivered_sign)
          into available_after
          from ledger_entry e join ledger_kind k on k.kind = e.kind
         where e.case_id = new.case_id;
        if available_after < 0 then
            raise exception 'a % of % would leave case % at %', new.kind, new.amount_cents, owner_code, available_after
                using errcode = 'check_violation';
        end if;
    end if;

    select coalesce(sum(amount_cents), 0) + new.amount_cents into gross from ledger_entry;
    if gross > 1000000000000000 then
        raise exception 'the platform-wide movement total would exceed 10^15 cents' using errcode = 'check_violation';
    end if;

    return new;
end $$;

create trigger ledger_entry_check before insert on ledger_entry
    for each row execute function ledger_entry_check();

-- Transfers: checked when the transaction commits, so both sides can be inserted first.
-- A pair is exactly two entries of opposite kinds, same amount and instant, on two cases.
-- A reversal pair corrects the two sides of one original transfer.
create function ledger_pair_check() returns trigger language plpgsql as $$
declare
    members  integer;
    ok       boolean;
begin
    if new.pair_id is null then
        return null;
    end if;
    select count(*) into members from ledger_entry where pair_id = new.pair_id;
    select exists (
        select 1
          from ledger_entry a
          join ledger_kind ka on ka.kind = a.kind
          join ledger_entry b on b.pair_id = a.pair_id and b.id <> a.id
          left join ledger_entry ta on ta.id = a.corrects_id
          left join ledger_entry tb on tb.id = b.corrects_id
         where a.id = new.id
           and b.kind = ka.paired_with
           and b.amount_cents = a.amount_cents
           and b.effective_at = a.effective_at
           and b.case_id <> a.case_id
           and (ka.corrects_kind is null or (ta.pair_id is not null and ta.pair_id = tb.pair_id))
    ) into ok;
    if members <> 2 or not ok then
        raise exception 'transfer pair % needs two opposite entries with the same amount and instant on two different cases', new.pair_id
            using errcode = 'check_violation';
    end if;
    return null;
end $$;

create constraint trigger ledger_pair_check after insert on ledger_entry
    deferrable initially deferred for each row execute function ledger_pair_check();

-- Who gave, and how they agreed to appear. Becomes part of the donations module when
-- donation reporting is built; the columns the public read model needs are here now.
create table donation_listing (
    ledger_entry_id  bigint primary key references ledger_entry (id),
    public_id        text collate "C" not null unique check (public_id ~ '^[A-Za-z0-9_-]{1,40}$'),
    visibility       text not null check (visibility in ('named', 'anonymous', 'hidden')),
    donor_name       text,
    avatar_media_id  bigint references media_asset (id),
    avatar_consent   boolean not null default false
);

comment on column donation_listing.public_id is 'Opaque identifier of the listing. Not an account identifier.';
comment on column donation_listing.avatar_consent is 'Separate from visibility: agreeing to be named is not agreeing to show a picture.';

create function donation_listing_check() returns trigger language plpgsql as $$
begin
    if (select kind from ledger_entry where id = new.ledger_entry_id) <> 'donation' then
        raise exception 'a donor listing belongs to a donation entry' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger donation_listing_check before insert or update on donation_listing
    for each row execute function donation_listing_check();

-- ---------------------------------------------------------------------------------------
-- Read interface of the ledger module. Other modules read these views, never ledger_entry.
-- ---------------------------------------------------------------------------------------

-- Money breakdown of every case. sum() of bigint is numeric in PostgreSQL: exact, no overflow.
create view case_balance as
    select c.id as case_id,
           coalesce(sum(e.amount_cents) filter (where e.kind = 'donation'), 0)
             - coalesce(sum(e.amount_cents) filter (where e.kind in ('donation_reversal', 'refund')), 0) as external_donations,
           coalesce(sum(e.amount_cents) filter (where e.kind = 'transfer_in'), 0)
             - coalesce(sum(e.amount_cents) filter (where e.kind = 'transfer_in_reversal'), 0) as transfers_in,
           coalesce(sum(e.amount_cents) filter (where e.kind = 'transfer_out'), 0)
             - coalesce(sum(e.amount_cents) filter (where e.kind = 'transfer_out_reversal'), 0) as transfers_out,
           coalesce(sum(e.amount_cents * k.allocated_sign), 0) as allocated,
           coalesce(sum(e.amount_cents * k.delivered_sign), 0) as delivered
      from aid_case c
      left join ledger_entry e on e.case_id = c.id
      left join ledger_kind k on k.kind = e.kind
     group by c.id;

-- Every entry with what corrects it and, for transfers, the case at the other end.
create view ledger_entry_detail as
    select e.id,
           e.public_id,
           e.case_id,
           e.kind,
           e.amount_cents,
           e.amount_cents * k.allocated_sign as allocated_delta,
           e.amount_cents * k.delivered_sign as delivered_delta,
           e.effective_at,
           k.label_en,
           k.label_fr,
           target.public_id as corrects_public_id,
           coalesce(fix.ids, array[]::text[]) as corrected_by,
           coalesce(fix.total, 0) as corrected_cents,
           other_case.code as counterpart_code
      from ledger_entry e
      join ledger_kind k on k.kind = e.kind
      left join ledger_entry target on target.id = e.corrects_id
      left join lateral (
          select array_agg(c.public_id::text order by c.effective_at, c.public_id) as ids,
                 sum(c.amount_cents) as total
            from ledger_entry c
           where c.corrects_id = e.id
      ) fix on true
      left join ledger_entry other on other.pair_id = e.pair_id and other.id <> e.id
      left join aid_case other_case on other_case.id = other.case_id;
