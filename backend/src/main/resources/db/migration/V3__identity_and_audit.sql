-- APV-GAKASINAY, milestone 1B: accounts, credentials, second factor, sessions, rate limits
-- and the security audit log. Rules: contract/identity-and-security.md.
--
-- Module ownership: every table here belongs to the identity module, except audit_event,
-- which belongs to the audit module. No public read query touches any of them.

-- ---------------------------------------------------------------------------------------
-- The guard: one row. Every transaction that can change who the usable super
-- administrators are locks it first (select ... for update), so those transactions run one
-- after another. It also records two one-way facts about this database.
-- ---------------------------------------------------------------------------------------

create table identity_guard (
    singleton      boolean primary key default true check (singleton),
    bootstrapped   boolean not null default false,
    database_real  boolean not null default false
);

comment on column identity_guard.bootstrapped is
    'True once a super administrator has been usable for the first time. Never unset. Before it, zero usable super administrators is allowed.';
comment on column identity_guard.database_real is
    'True once the operator command has been used on this database. Never unset. A real database never accepts fictional data.';

insert into identity_guard default values;

create function identity_guard_is_one_way() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'the identity guard row is never deleted' using errcode = 'restrict_violation';
    end if;
    if (old.bootstrapped and not new.bootstrapped) or (old.database_real and not new.database_real) then
        raise exception 'identity_guard flags are one-way and cannot be unset' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger identity_guard_one_way before update or delete on identity_guard
    for each row execute function identity_guard_is_one_way();

-- ---------------------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------------------

create table account (
    id                         bigint generated always as identity primary key,
    public_id                  text collate "C" not null unique check (public_id ~ '^acc_[A-Za-z0-9]{22}$'),
    email                      text not null check (char_length(email) between 6 and 254),
    email_normalized           text collate "C" not null unique
                               check (email_normalized = lower(email_normalized) and email_normalized !~ '\s'),
    display_name               text not null check (char_length(display_name) between 1 and 80),
    locale                     text not null default 'en' check (locale in ('en', 'fr')),
    role                       text not null check (role in ('donor', 'admin', 'super_admin')),
    can_disburse               boolean not null default false,
    enabled                    boolean not null default true,
    activated                  boolean not null default true,
    enrollment_window_ends_at  timestamptz,
    email_verified             boolean not null default false,
    security_version           bigint not null default 1 check (security_version >= 1),
    created_at                 timestamptz not null check (created_at = date_trunc('second', created_at)),

    constraint account_can_disburse_is_for_admins check (not can_disburse or role = 'admin')
);

comment on column account.public_id is 'Opaque identifier shown to its owner. Assigned once, never changed, never reused, not derived from the email.';
comment on column account.email_normalized is 'Lower-cased address. Uniqueness here is what makes two simultaneous sign-ups produce one account.';
comment on column account.activated is 'False for a staff account that has no usable password: before activation, after staff recovery, or after its enrollment window closed.';
comment on column account.enrollment_window_ends_at is 'A staff account may set up its second factor only before this instant. Opened by redeeming an activation or staff-recovery credential.';
comment on column account.security_version is 'Raised by every change that must end existing sessions. A session whose stored version differs is refused.';

create function account_identity_is_immutable() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'accounts are disabled, never deleted' using errcode = 'restrict_violation';
    end if;
    if old.public_id <> new.public_id or old.id <> new.id then
        raise exception 'account identifiers never change' using errcode = 'check_violation';
    end if;
    if new.security_version < old.security_version then
        raise exception 'the security version only increases' using errcode = 'check_violation';
    end if;
    return new;
end $$;

create trigger account_identity_immutable before update or delete on account
    for each row execute function account_identity_is_immutable();

-- The hash lives apart from the account, so the query that builds an account response cannot
-- select it by accident. No row means no usable password.
create table account_password (
    account_id     bigint primary key references account (id),
    password_hash  text not null check (password_hash like '{%}$argon2id$%'),
    changed_at     timestamptz not null
);

create table account_totp (
    account_id          bigint primary key references account (id),
    secret_encrypted    bytea not null,
    state               text not null check (state in ('pending', 'active')),
    pending_expires_at  timestamptz,
    last_accepted_step  bigint not null default -1,
    activated_at        timestamptz,
    activated_by_session bytea,
    constraint account_totp_state_shape check (
        (state = 'pending' and pending_expires_at is not null and activated_at is null)
        or (state = 'active' and pending_expires_at is null and activated_at is not null))
);

comment on column account_totp.secret_encrypted is 'AES-256-GCM under a key that is not in the database: nonce, then ciphertext and tag.';
comment on column account_totp.activated_by_session is 'SHA-256 of the identifier the pending session had when it activated the factor. That identifier was replaced at once; the hash only tells a simultaneous request on the same session from one on another.';
comment on column account_totp.last_accepted_step is 'A code is accepted by moving this forward in one conditional update, so it works once even when sent twice at the same instant.';

create table account_recovery_code (
    id          bigint generated always as identity primary key,
    account_id  bigint not null references account (id),
    code_hash   bytea not null,
    used_at     timestamptz,
    constraint account_recovery_code_unique unique (account_id, code_hash)
);

comment on column account_recovery_code.code_hash is 'HMAC-SHA-256 of the code under a server-side key. The code itself is shown once and never stored.';

create table one_time_credential (
    id               bigint generated always as identity primary key,
    credential_hash  bytea not null unique,
    account_id       bigint not null references account (id),
    purpose          text not null check (purpose in ('activation', 'password_reset', 'staff_recovery')),
    created_at       timestamptz not null,
    expires_at       timestamptz not null,
    used_at          timestamptz,
    cancelled_at     timestamptz
);

create index one_time_credential_by_account on one_time_credential (account_id) where used_at is null and cancelled_at is null;

-- ---------------------------------------------------------------------------------------
-- The usable super administrator invariant (identity-and-security.md, 8.6).
--
-- The guard row is what makes concurrent writers safe. This trigger is the backstop: it runs
-- at commit and refuses a transaction that, by itself, would leave a bootstrapped site with
-- no usable super administrator, even if that transaction forgot to take the guard.
-- ---------------------------------------------------------------------------------------

create view usable_super_administrator as
    select a.id
      from account a
      join account_totp t on t.account_id = a.id and t.state = 'active'
     where a.role = 'super_admin' and a.enabled and a.activated;

create function usable_super_administrator_check() returns trigger language plpgsql as $$
begin
    if (select bootstrapped from identity_guard) and not exists (select 1 from usable_super_administrator) then
        raise exception 'this change would leave no usable super administrator' using errcode = 'check_violation';
    end if;
    return null;
end $$;

create constraint trigger account_keeps_a_super_administrator after update or delete on account
    deferrable initially deferred for each row execute function usable_super_administrator_check();

create constraint trigger account_totp_keeps_a_super_administrator after update or delete on account_totp
    deferrable initially deferred for each row execute function usable_super_administrator_check();

-- ---------------------------------------------------------------------------------------
-- Sessions: the tables Spring Session JDBC documents for PostgreSQL, created here and not by
-- automatic initialization.
-- ---------------------------------------------------------------------------------------

create table spring_session (
    primary_id             char(36) not null,
    session_id             char(36) not null,
    creation_time          bigint not null,
    last_access_time       bigint not null,
    max_inactive_interval  int not null,
    expiry_time            bigint not null,
    principal_name         varchar(100),
    constraint spring_session_pk primary key (primary_id)
);

create unique index spring_session_ix1 on spring_session (session_id);
create index spring_session_ix2 on spring_session (expiry_time);
create index spring_session_ix3 on spring_session (principal_name);

create table spring_session_attributes (
    session_primary_id  char(36) not null,
    attribute_name      varchar(200) not null,
    attribute_bytes     bytea not null,
    constraint spring_session_attributes_pk primary key (session_primary_id, attribute_name),
    constraint spring_session_attributes_fk foreign key (session_primary_id) references spring_session (primary_id) on delete cascade
);

-- ---------------------------------------------------------------------------------------
-- Rate-limit counters. The key is a keyed hash, never an email or an address.
-- ---------------------------------------------------------------------------------------

create table auth_throttle (
    scope         text not null,
    key_hash      bytea not null,
    window_start  timestamptz not null,
    attempts      integer not null check (attempts >= 0),
    constraint auth_throttle_pk primary key (scope, key_hash, window_start)
);

create index auth_throttle_by_window on auth_throttle (window_start);

-- ---------------------------------------------------------------------------------------
-- Security audit log (module: audit). Append-only, like the ledger.
-- ---------------------------------------------------------------------------------------

create table audit_event (
    id                 bigint generated always as identity primary key,
    occurred_at        timestamptz not null default now(),
    event_type         text not null check (event_type ~ '^[a-z][a-z0-9_.]{2,63}$'),
    outcome            text not null check (outcome in ('success', 'failure', 'refused')),
    actor_account_id   bigint,
    actor_operator     text,
    target_account_id  bigint,
    client_address     text,
    request_id         text,
    details            jsonb not null default '{}'::jsonb
);

create index audit_event_by_time on audit_event (occurred_at desc);
create index audit_event_by_target on audit_event (target_account_id, occurred_at desc) where target_account_id is not null;

comment on table audit_event is
    'Never holds a password, hash, session identifier, CSRF token, authenticator secret, code or one-time credential. details is built from a fixed set of keys per event type. Generic on purpose: financial administration events will use it too.';

create function audit_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'the audit log is append-only: % is not allowed', tg_op using errcode = 'restrict_violation';
end $$;

create trigger audit_event_append_only before update or delete on audit_event
    for each row execute function audit_is_append_only();
