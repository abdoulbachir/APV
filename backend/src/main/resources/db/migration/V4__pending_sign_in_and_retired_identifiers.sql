-- APV-GAKASINAY, milestone 1B corrective: the pending sign-in budget moves into the
-- database, and a session identifier that has been replaced can never come back.

-- ---------------------------------------------------------------------------------------
-- One row per pending sign-in (password accepted, second factor or enrollment owed).
--
-- The five wrong codes a pending sign-in is allowed were counted in a session attribute.
-- Two simultaneous requests could then read the same count and both write the same next
-- value. Here every request of one pending sign-in locks this row first, on whatever
-- instance it runs, so wrong codes are counted exactly and a correct code can never be
-- accepted after the fifth wrong one.
-- ---------------------------------------------------------------------------------------

create table pending_sign_in (
    id          bytea primary key check (length(id) = 32),
    account_id  bigint not null references account (id),
    failures    integer not null default 0 check (failures between 0 and 5),
    outcome     text check (outcome in ('completed', 'exhausted')),
    expires_at  timestamptz not null,
    constraint pending_sign_in_exhausted_at_five check ((outcome = 'exhausted') = (failures = 5))
);

create index pending_sign_in_by_expiry on pending_sign_in (expires_at);

comment on column pending_sign_in.id is 'Random, held only in the server-side session. Not derived from the session identifier.';
comment on column pending_sign_in.outcome is 'Null while the sign-in is pending. completed: a correct code was accepted. exhausted: the fifth wrong code. Either way no further code is ever checked.';

-- ---------------------------------------------------------------------------------------
-- A replaced session identifier never returns.
--
-- The session store writes a session's identifier back at the end of every request that
-- used it. A request that began before a rotation (sign-in, second factor, password
-- change) and ends after it would write the old identifier back over the new one. The old
-- identifier is recorded when it is replaced, and a later write that tries to restore it
-- is discarded whole: the row stays exactly as the rotation left it.
-- ---------------------------------------------------------------------------------------

create table spring_session_retired (
    session_primary_id  char(36) not null references spring_session (primary_id) on delete cascade,
    session_id          char(36) not null,
    constraint spring_session_retired_pk primary key (session_primary_id, session_id)
);

create function spring_session_identifier_never_returns() returns trigger language plpgsql as $$
begin
    if exists (select 1 from spring_session_retired r
                where r.session_primary_id = old.primary_id and r.session_id = new.session_id) then
        -- A stale writer. Nothing it holds is newer than what is stored.
        return old;
    end if;
    insert into spring_session_retired (session_primary_id, session_id) values (old.primary_id, old.session_id);
    return new;
end $$;

create trigger spring_session_identifier_rotation before update on spring_session
    for each row when (old.session_id is distinct from new.session_id)
    execute function spring_session_identifier_never_returns();
