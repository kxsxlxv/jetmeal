-- Optional body-weight trajectory. Stored separately from calorie/macro goals.
create table if not exists public.weight_goals (
    owner_id uuid primary key references public.profiles(id) on delete cascade,
    start_date date not null,
    target_date date not null check (target_date>start_date),
    start_weight_kg numeric(7,3) not null check (start_weight_kg between 20 and 500),
    target_weight_kg numeric(7,3) not null check (target_weight_kg between 20 and 500),
    updated_at timestamptz not null default now()
);
alter table public.weight_goals enable row level security;
revoke all on public.weight_goals from public,anon,authenticated;
grant select,insert,update on public.weight_goals to authenticated;
create policy "weight_goal_select_own" on public.weight_goals
    for select to authenticated using ((select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false));
create policy "weight_goal_insert_own" on public.weight_goals
    for insert to authenticated with check ((select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false));
create policy "weight_goal_update_own" on public.weight_goals
    for update to authenticated using ((select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false))
    with check ((select auth.uid())=owner_id
      and not coalesce((select auth.jwt()->>'is_anonymous')::boolean,false));

create or replace function public.jetmeal_set_weight_goal(
    p_start_weight_kg numeric,p_target_weight_kg numeric,p_target_date date
) returns boolean language plpgsql security invoker set search_path=''
as $$
declare
    uid uuid := (select auth.uid());
    zone text;
    today date;
begin
    if uid is null or coalesce((select auth.jwt()->>'is_anonymous')::boolean,false) then
        raise exception 'Registered user required' using errcode='42501';
    end if;
    select timezone into zone from public.profiles where id=uid;
    today := (now() at time zone coalesce(zone,'UTC'))::date;
    if p_start_weight_kg is null or p_start_weight_kg not between 20 and 500
        or p_target_weight_kg is null or p_target_weight_kg not between 20 and 500
        or p_target_date is null or p_target_date<=today or p_target_date>today+730 then
        raise exception 'Invalid weight goal' using errcode='22023';
    end if;
    insert into public.weight_goals(owner_id,start_date,target_date,start_weight_kg,target_weight_kg)
    values(uid,today,p_target_date,p_start_weight_kg,p_target_weight_kg)
    on conflict(owner_id) do update set
        start_date=excluded.start_date,target_date=excluded.target_date,
        start_weight_kg=excluded.start_weight_kg,
        target_weight_kg=excluded.target_weight_kg,updated_at=now();
    return true;
end;
$$;
revoke all on function public.jetmeal_set_weight_goal(numeric,numeric,date) from public,anon;
grant execute on function public.jetmeal_set_weight_goal(numeric,numeric,date) to authenticated;
