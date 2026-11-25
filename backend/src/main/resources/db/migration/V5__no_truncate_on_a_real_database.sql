-- APV-GAKASINAY, milestone 1B: TRUNCATE is refused on a real database.
--
-- The audit log and the ledger are append-only, accounts and the identity guard are never
-- deleted, and a bootstrapped site always keeps a usable super administrator. All of that is
-- enforced by row triggers, and row triggers do not fire on TRUNCATE: one statement emptied
-- the audit log in the independent review (finding D5).
--
-- A statement-level trigger closes that on every table whose protection is a row trigger.
-- TRUNCATE stays possible in exactly one situation, the one it exists for here: resetting a
-- disposable database, which is how the development fixture loader and the tests start
-- over. Disposable has the meaning it has everywhere else (identity-and-security.md, 10.2):
-- the database was marked so when it was created, and the operator command has never been
-- used on it. Adding the marker later to a real database changes nothing, because the
-- second condition is one-way and is itself protected here.
--
-- Limit, stated plainly: the application's role owns these tables, and an owner can drop a
-- trigger. This stops a TRUNCATE statement; it does not make the owner powerless. Running
-- the application under a role that does not own the schema is a deployment decision.

create function truncate_only_on_a_disposable_database() returns trigger language plpgsql as $$
begin
    if exists (select 1 from pg_catalog.pg_namespace where nspname = 'apv_disposable_database')
       and not coalesce((select bool_or(database_real) from identity_guard), false) then
        return null;
    end if;
    raise exception '% is never truncated on a real database', tg_table_name using errcode = 'restrict_violation';
end $$;

-- Append-only records.
create trigger audit_event_no_truncate before truncate on audit_event
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger ledger_entry_no_truncate before truncate on ledger_entry
    for each statement execute function truncate_only_on_a_disposable_database();

-- The running total the ledger triggers maintain. Emptied, the platform bound would be gone.
create trigger ledger_platform_total_no_truncate before truncate on ledger_platform_total
    for each statement execute function truncate_only_on_a_disposable_database();

-- Never deleted: the guard row with its one-way flags, and accounts.
create trigger identity_guard_no_truncate before truncate on identity_guard
    for each statement execute function truncate_only_on_a_disposable_database();
create trigger account_no_truncate before truncate on account
    for each statement execute function truncate_only_on_a_disposable_database();

-- The usable super administrator rule looks at accounts and at their second factor.
create trigger account_totp_no_truncate before truncate on account_totp
    for each statement execute function truncate_only_on_a_disposable_database();

-- A replaced session identifier never returns: that record is kept by a row trigger too.
create trigger spring_session_retired_no_truncate before truncate on spring_session_retired
    for each statement execute function truncate_only_on_a_disposable_database();
