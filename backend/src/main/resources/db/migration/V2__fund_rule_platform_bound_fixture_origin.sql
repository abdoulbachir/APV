-- Follow-up to the milestone 0B review.
--
--   A. The general fund never pays a beneficiary directly: its money is transferred to a case
--      first. Now enforced here, not only by convention.
--   C. The platform-wide bound (sum of all movement amounts at most 10^15 cents) was checked
--      by summing the ledger, which two simultaneous inserts on different cases could both
--      pass. It is now a single counter row: every insert updates it, so inserts take its row
--      lock one after the other and the bound is a plain check constraint on the new value.
--      This holds at every isolation level; under repeatable read or serializable the second
--      writer gets a serialization failure and must retry, it never passes silently.
--   D. organization_settings.data_origin records whether the data came from a fixture, so the
--      development fixture loader can refuse to replace a database that ever held real data.

alter table organization_settings
    add column data_origin text not null default 'real' check (data_origin in ('real', 'fixture'));

comment on column organization_settings.data_origin is
    'real, or fixture when the content was loaded by the development fixture importer. The importer only replaces an empty database or one marked fixture.';

create table ledger_platform_total (
    singleton    boolean primary key default true check (singleton),
    gross_cents  numeric(20, 0) not null,
    constraint ledger_platform_total_within_bound check (gross_cents between 0 and 1000000000000000)
);

comment on table ledger_platform_total is
    'One row: the sum of amount_cents over the whole ledger. Maintained by the ledger triggers only.';

insert into ledger_platform_total (gross_cents)
    select coalesce(sum(amount_cents), 0) from ledger_entry;

-- TRUNCATE is how development and test data are reset; it bypasses row triggers.
create function ledger_platform_total_reset() returns trigger language plpgsql as $$
begin
    update ledger_platform_total set gross_cents = 0;
    return null;
end $$;

create trigger ledger_entry_truncated after truncate on ledger_entry
    for each statement execute function ledger_platform_total_reset();

create or replace function ledger_entry_check() returns trigger language plpgsql as $$
declare
    kind_rule       ledger_kind%rowtype;
    owner_status    text;
    owner_code      text;
    owner_kind      text;
    target          ledger_entry%rowtype;
    already         numeric;
    available_after numeric;
begin
    select * into kind_rule from ledger_kind where kind = new.kind;

    -- Serializes movements on one case, and gives a stable view of its status.
    select status, code, kind into owner_status, owner_code, owner_kind from aid_case where id = new.case_id for update;
    if owner_status = 'draft' then
        raise exception 'no money can move on draft case %', owner_code using errcode = 'check_violation';
    end if;

    if owner_kind = 'fund' and kind_rule.delivered_sign <> 0 then
        raise exception 'the general fund does not pay beneficiaries directly: transfer the money to a case first'
            using errcode = 'check_violation';
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

    -- Takes the counter's row lock, so every ledger insert on the platform passes here one at
    -- a time. The check constraint ledger_platform_total_within_bound refuses a total above 10^15.
    update ledger_platform_total set gross_cents = gross_cents + new.amount_cents;

    return new;
end $$;
