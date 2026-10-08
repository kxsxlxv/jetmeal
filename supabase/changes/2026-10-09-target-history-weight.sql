-- JetMeal: immutable-as-of-day nutrition targets and weight measurements.
-- Additive change; pre-existing diary entries and legacy objects remain untouched.
create table if not exists public.nutrition_target_history (
    owner_id uuid not null references public.profiles(id) on delete cascade,
    effective_date date not null,
    daily_calories_kcal numeric(10,3) not null check (daily_calories_kcal > 0),
    daily_protein_g numeric(10,3) not null check (daily_protein_g >= 0),
    daily_fat_g numeric(10,3) not null check (daily_fat_g >= 0),
    daily_carbs_g numeric(10,3) not null check (daily_carbs_g >= 0),
    adjustment_limit_ratio numeric(5,4) not null check (adjustment_limit_ratio between 0 and 1),
    recorded_at timestamptz not null default now(),
    primary key (owner_id, effective_date)
);
alter table public.nutrition_target_history enable row level security;
revoke all on public.nutrition_target_history from public,anon,authenticated;
grant select on public.nutrition_target_history to authenticated;
create policy "target_history_select_own" on public.nutrition_target_history
    for select to authenticated using (
        (select auth.uid()) = owner_id
        and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)
    );

-- At first migration, today's saved targets are the oldest provable baseline.
-- We retain them for earlier dates as a legacy assumption; source data before
-- the first version may not reconstruct earlier changes that were never audited.
insert into public.nutrition_target_history (
    owner_id,effective_date,daily_calories_kcal,daily_protein_g,daily_fat_g,
    daily_carbs_g,adjustment_limit_ratio
)
select owner_id, date '0001-01-01',daily_calories_kcal,daily_protein_g,daily_fat_g,
    daily_carbs_g,adjustment_limit_ratio
from public.nutrition_targets
on conflict (owner_id,effective_date) do nothing;

create or replace function private.version_jetmeal_targets()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
    zone text;
    effective date;
begin
    select timezone into zone from public.profiles where id=new.owner_id;
    effective := (now() at time zone coalesce(zone,'UTC'))::date;
    if tg_op = 'UPDATE' and
       (new.daily_calories_kcal,new.daily_protein_g,new.daily_fat_g,
        new.daily_carbs_g,new.adjustment_limit_ratio)
       is not distinct from
       (old.daily_calories_kcal,old.daily_protein_g,old.daily_fat_g,
        old.daily_carbs_g,old.adjustment_limit_ratio) then
        return new;
    end if;
    insert into public.nutrition_target_history (
        owner_id,effective_date,daily_calories_kcal,daily_protein_g,daily_fat_g,
        daily_carbs_g,adjustment_limit_ratio
    ) values (new.owner_id,effective,new.daily_calories_kcal,new.daily_protein_g,
        new.daily_fat_g,new.daily_carbs_g,new.adjustment_limit_ratio)
    on conflict (owner_id,effective_date) do update set
        daily_calories_kcal=excluded.daily_calories_kcal,
        daily_protein_g=excluded.daily_protein_g,
        daily_fat_g=excluded.daily_fat_g,
        daily_carbs_g=excluded.daily_carbs_g,
        adjustment_limit_ratio=excluded.adjustment_limit_ratio,
        recorded_at=now();
    return new;
end;
$$;
revoke all on function private.version_jetmeal_targets() from public,anon,authenticated;
create trigger jetmeal_version_nutrition_targets
    after insert or update on public.nutrition_targets
    for each row execute function private.version_jetmeal_targets();

create table if not exists public.weight_measurements (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    measured_at timestamptz not null,
    weight_kg numeric(7,3) not null check (weight_kg between 20 and 500),
    body_fat_percent numeric(5,2) check (body_fat_percent between 0 and 100),
    source text not null check (source in ('manual','picooc','health_connect')),
    external_id text,
    created_at timestamptz not null default now(),
    constraint imported_weight_needs_id check (source='manual' or (external_id is not null and length(external_id)>0)),
    constraint weight_external_identity unique (owner_id,source,external_id)
);
create index weight_owner_time_idx on public.weight_measurements(owner_id,measured_at desc);
alter table public.weight_measurements enable row level security;
revoke all on public.weight_measurements from public,anon,authenticated;
grant select,insert,update on public.weight_measurements to authenticated;
create policy "weight_select_own" on public.weight_measurements
    for select to authenticated using (
      (select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)
    );
create policy "weight_insert_own" on public.weight_measurements
    for insert to authenticated with check (
      (select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)
    );
create policy "weight_update_own" on public.weight_measurements
    for update to authenticated
    using ((select auth.uid())=owner_id and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false))
    with check ((select auth.uid())=owner_id and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false));

-- A typed operation used by both manual and imported weights.
create or replace function public.jetmeal_log_weight(
    p_weight_kg numeric,
    p_measured_at timestamptz,
    p_source text default 'manual',
    p_external_id text default null,
    p_body_fat_percent numeric default null
) returns uuid language plpgsql security invoker set search_path = ''
as $$
declare
    result uuid;
    uid uuid := (select auth.uid());
begin
    if uid is null or coalesce((select auth.jwt()->>'is_anonymous')::boolean,false) then
        raise exception 'Registered user required' using errcode='42501';
    end if;
    if p_weight_kg is null or p_weight_kg < 20 or p_weight_kg > 500
        or p_measured_at is null or p_measured_at > now()+interval '1 day'
        or p_source not in ('manual','picooc','health_connect')
        or (p_source <> 'manual' and (p_external_id is null or length(p_external_id)=0))
        or (p_body_fat_percent is not null and (p_body_fat_percent<0 or p_body_fat_percent>100)) then
        raise exception 'Invalid weight measurement' using errcode='22023';
    end if;
    insert into public.weight_measurements(owner_id,weight_kg,measured_at,source,external_id,body_fat_percent)
    values(uid,p_weight_kg,p_measured_at,p_source,p_external_id,p_body_fat_percent)
    on conflict (owner_id,source,external_id) do update set
        weight_kg=excluded.weight_kg,
        measured_at=excluded.measured_at,
        body_fat_percent=excluded.body_fat_percent
    returning id into result;
    return result;
end;
$$;
revoke all on function public.jetmeal_log_weight(numeric,timestamptz,text,text,numeric) from public,anon;
grant execute on function public.jetmeal_log_weight(numeric,timestamptz,text,text,numeric) to authenticated;
