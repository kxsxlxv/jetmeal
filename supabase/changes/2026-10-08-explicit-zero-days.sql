-- Additive day-completeness marker: an absent past day is unknown, not zero.
-- Keep this separate from the historical hosted rollout. No existing diary rows change.
create table if not exists public.zero_calorie_days (
    owner_id uuid not null references public.profiles(id) on delete cascade,
    local_date date not null,
    confirmed_at timestamptz not null default now(),
    primary key (owner_id, local_date)
);
alter table public.zero_calorie_days enable row level security;
revoke all on table public.zero_calorie_days from public, anon, authenticated;
grant select on table public.zero_calorie_days to authenticated;
create policy "zero_calorie_days_select_own" on public.zero_calorie_days
    for select to authenticated
    using ((select auth.uid()) = owner_id and not coalesce((select auth.jwt()->>'is_anonymous')::boolean, false));

-- Confirm only past days with NO active diary entry. Remove confirmation any time.
-- Explicitly scope the SECURITY DEFINER routine to auth.uid(); clients cannot
-- INSERT or DELETE confirmations through the Data API.
create or replace function public.jetmeal_confirm_zero_day(
    p_date date,
    p_confirmed boolean
) returns boolean language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := (select auth.uid());
    zone text;
begin
    if uid is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then
        raise exception 'Authentication required' using errcode='42501';
    end if;
    if p_date is null or p_confirmed is null then
        raise exception 'Date and confirmation are required' using errcode='22023';
    end if;
    select timezone into zone from public.profiles where id=uid for update;
    if zone is null then
        raise exception 'Profile not found' using errcode='42501';
    end if;
    if p_confirmed then
        if p_date >= (now() at time zone zone)::date then
            raise exception 'Only past days can be confirmed empty' using errcode='22023';
        end if;
        if exists (
            select 1 from public.diary_entries e
            where e.owner_id=uid and e.deleted_at is null
              and e.consumed_at >= (p_date::timestamp at time zone zone)
              and e.consumed_at < ((p_date+1)::timestamp at time zone zone)
        ) then
            raise exception 'Day already has diary entries' using errcode='23514';
        end if;
        insert into public.zero_calorie_days(owner_id,local_date)
        values(uid,p_date) on conflict(owner_id,local_date) do nothing;
    else
        delete from public.zero_calorie_days
        where owner_id=uid and local_date=p_date;
    end if;
    return p_confirmed;
end;
$$;
revoke all on function public.jetmeal_confirm_zero_day(date,boolean) from public, anon;
grant execute on function public.jetmeal_confirm_zero_day(date,boolean) to authenticated;

-- If food is subsequently logged/moved/restored on a confirmed day,
-- atomically retract the stale zero-day marker.
create or replace function private.clear_zero_day_on_diary_write()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
    zone text;
begin
    if new.deleted_at is null then
        select timezone into zone from public.profiles where id=new.owner_id;
        if zone is not null then
            delete from public.zero_calorie_days
            where owner_id=new.owner_id
              and local_date=(new.consumed_at at time zone zone)::date;
        end if;
    end if;
    return new;
end;
$$;
revoke all on function private.clear_zero_day_on_diary_write() from public, anon, authenticated;
create trigger jetmeal_clear_zero_day_on_diary_write
    after insert or update of consumed_at, deleted_at on public.diary_entries
    for each row execute function private.clear_zero_day_on_diary_write();
