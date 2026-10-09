-- JetMeal database bootstrap for a fresh user-owned Supabase project.
-- This is intentionally a bootstrap script, not generated migration history.

begin;

create extension if not exists pgcrypto;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    -- Synchronized from the Android system timezone; not a user-facing preference.
    timezone text not null default 'UTC' check (length(trim(timezone)) > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.foods (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    name text not null check (length(trim(name)) > 0),
    normalized_name text not null check (length(trim(normalized_name)) > 0),
    kind text not null check (kind in ('generic', 'packaged', 'restaurant', 'canteen', 'ai_estimate')),
    brand text,
    source text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (id, owner_id)
);

create table if not exists public.food_variants (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    food_id uuid not null,
    -- Nutrition values below describe this base serving. Examples: 300 g, 250 ml, 1 piece.
    serving_amount numeric(10,3) not null check (serving_amount > 0),
    serving_unit text not null check (length(trim(serving_unit)) > 0),
    calories_kcal numeric(10,3) not null check (calories_kcal >= 0),
    protein_g numeric(10,3) not null check (protein_g >= 0),
    fat_g numeric(10,3) not null check (fat_g >= 0),
    carbs_g numeric(10,3) not null check (carbs_g >= 0),
    is_estimated boolean not null default false,
    source_note text,
    supersedes_variant_id uuid,
    created_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (id, owner_id),
    constraint food_variants_food_owner_fk
        foreign key (food_id, owner_id)
        references public.foods(id, owner_id)
        on delete restrict,
    constraint food_variants_supersedes_fk
        foreign key (supersedes_variant_id)
        references public.food_variants(id)
        on delete restrict
);

create table if not exists public.diary_entries (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    food_id uuid,
    food_variant_id uuid,
    meal_group_id uuid,
    meal_type text not null check (meal_type in ('morning', 'day', 'evening', 'snack')),
    snapshot_name text not null check (length(trim(snapshot_name)) > 0),
    snapshot_brand text,

    -- Immutable nutritional basis for proportional quantity corrections.
    -- Normally copied from the chosen food_variant at log time. For an ad-hoc AI estimate,
    -- the initial logged quantity/nutrition becomes the basis.
    basis_amount_snapshot numeric(10,3) not null check (basis_amount_snapshot > 0),
    basis_unit_snapshot text not null check (length(trim(basis_unit_snapshot)) > 0),
    basis_calories_kcal_snapshot numeric(10,3) not null check (basis_calories_kcal_snapshot >= 0),
    basis_protein_g_snapshot numeric(10,3) not null check (basis_protein_g_snapshot >= 0),
    basis_fat_g_snapshot numeric(10,3) not null check (basis_fat_g_snapshot >= 0),
    basis_carbs_g_snapshot numeric(10,3) not null check (basis_carbs_g_snapshot >= 0),

    -- Current consumed quantity and current totals. Explicit quantity corrections may update these.
    quantity numeric(10,3) not null check (quantity > 0),
    quantity_unit text not null check (length(trim(quantity_unit)) > 0),
    calories_kcal_snapshot numeric(10,3) not null check (calories_kcal_snapshot >= 0),
    protein_g_snapshot numeric(10,3) not null check (protein_g_snapshot >= 0),
    fat_g_snapshot numeric(10,3) not null check (fat_g_snapshot >= 0),
    carbs_g_snapshot numeric(10,3) not null check (carbs_g_snapshot >= 0),

    confidence numeric(5,4) check (confidence is null or confidence between 0 and 1),
    consumed_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint diary_food_owner_fk
        foreign key (food_id, owner_id)
        references public.foods(id, owner_id)
        on delete restrict,
    constraint diary_variant_owner_fk
        foreign key (food_variant_id, owner_id)
        references public.food_variants(id, owner_id)
        on delete restrict
);

create table if not exists public.nutrition_targets (
    owner_id uuid primary key references public.profiles(id) on delete cascade,
    daily_calories_kcal numeric(10,3) not null check (daily_calories_kcal > 0),
    daily_protein_g numeric(10,3) not null default 0 check (daily_protein_g >= 0),
    daily_fat_g numeric(10,3) not null default 0 check (daily_fat_g >= 0),
    daily_carbs_g numeric(10,3) not null default 0 check (daily_carbs_g >= 0),
    -- Symmetric per-day bound for Monday-Sunday redistribution. 0.10 means ±10%.
    adjustment_limit_ratio numeric(5,4) not null default 0.10
        check (adjustment_limit_ratio between 0 and 1),
    updated_at timestamptz not null default now()
);

create table if not exists public.audit_events (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    action_group_id uuid not null,
    actor text not null check (actor in ('user', 'ai', 'system')),
    operation text not null check (operation in ('insert', 'update', 'soft_delete', 'undo', 'target_change')),
    entity_type text not null check (length(trim(entity_type)) > 0),
    entity_id uuid,
    before_state jsonb,
    after_state jsonb,
    created_at timestamptz not null default now()
);

create index if not exists foods_owner_normalized_name_idx
    on public.foods(owner_id, normalized_name)
    where archived_at is null;

create index if not exists food_variants_owner_food_idx
    on public.food_variants(owner_id, food_id, created_at desc)
    where archived_at is null;

create index if not exists diary_entries_owner_consumed_idx
    on public.diary_entries(owner_id, consumed_at desc)
    where deleted_at is null;

create index if not exists diary_entries_owner_meal_type_consumed_idx
    on public.diary_entries(owner_id, meal_type, consumed_at desc)
    where deleted_at is null;

create index if not exists diary_entries_owner_meal_group_idx
    on public.diary_entries(owner_id, meal_group_id)
    where deleted_at is null and meal_group_id is not null;

create index if not exists audit_events_owner_created_idx
    on public.audit_events(owner_id, created_at desc);

alter table public.profiles enable row level security;
alter table public.foods enable row level security;
alter table public.food_variants enable row level security;
alter table public.diary_entries enable row level security;
alter table public.nutrition_targets enable row level security;
alter table public.audit_events enable row level security;

revoke all on table public.profiles from anon;
revoke all on table public.foods from anon;
revoke all on table public.food_variants from anon;
revoke all on table public.diary_entries from anon;
revoke all on table public.nutrition_targets from anon;
revoke all on table public.audit_events from anon;

grant select, update on table public.profiles to authenticated;
grant select, insert, update on table public.foods to authenticated;
grant select, insert, update on table public.food_variants to authenticated;
grant select, insert, update on table public.diary_entries to authenticated;
grant select, insert, update on table public.nutrition_targets to authenticated;
grant select, insert on table public.audit_events to authenticated;

create policy "profiles_select_own" on public.profiles
    for select to authenticated
    using ((select auth.uid()) = id);

create policy "profiles_update_own" on public.profiles
    for update to authenticated
    using ((select auth.uid()) = id)
    with check ((select auth.uid()) = id);

create policy "foods_select_own" on public.foods
    for select to authenticated
    using ((select auth.uid()) = owner_id);

create policy "foods_insert_own" on public.foods
    for insert to authenticated
    with check ((select auth.uid()) = owner_id);

create policy "foods_update_own" on public.foods
    for update to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

create policy "food_variants_select_own" on public.food_variants
    for select to authenticated
    using ((select auth.uid()) = owner_id);

create policy "food_variants_insert_own" on public.food_variants
    for insert to authenticated
    with check ((select auth.uid()) = owner_id);

create policy "food_variants_update_own" on public.food_variants
    for update to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

create policy "diary_entries_select_own" on public.diary_entries
    for select to authenticated
    using ((select auth.uid()) = owner_id);

create policy "diary_entries_insert_own" on public.diary_entries
    for insert to authenticated
    with check ((select auth.uid()) = owner_id);

create policy "diary_entries_update_own" on public.diary_entries
    for update to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

create policy "nutrition_targets_select_own" on public.nutrition_targets
    for select to authenticated
    using ((select auth.uid()) = owner_id);

create policy "nutrition_targets_insert_own" on public.nutrition_targets
    for insert to authenticated
    with check ((select auth.uid()) = owner_id);

create policy "nutrition_targets_update_own" on public.nutrition_targets
    for update to authenticated
    using ((select auth.uid()) = owner_id)
    with check ((select auth.uid()) = owner_id);

create policy "audit_events_select_own" on public.audit_events
    for select to authenticated
    using ((select auth.uid()) = owner_id);

create policy "audit_events_insert_own" on public.audit_events
    for insert to authenticated
    with check ((select auth.uid()) = owner_id);

create or replace function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.profiles (id)
    values (new.id)
    on conflict (id) do nothing;
    return new;
end;
$$;

revoke all on function private.handle_new_user() from public, anon, authenticated;

drop trigger if exists jetmeal_on_auth_user_created on auth.users;
create trigger jetmeal_on_auth_user_created
    after insert on auth.users
    for each row execute function private.handle_new_user();

commit;

begin;

alter table public.audit_events add column event_sequence bigint generated always as identity;

-- numeric admits NaN/Infinity in PostgreSQL; finite bounded values only.
do $$
declare col record;
begin
    for col in select table_name,column_name from information_schema.columns
        where table_schema='public' and table_name in ('food_variants','diary_entries','nutrition_targets')
        and data_type='numeric' loop
        execute format('alter table public.%I add constraint %I check (%I between 0 and 1000000)',
            col.table_name,col.column_name || '_finite',col.column_name);
    end loop;
end $$;

-- All nutrition mutations go through atomic application operations. Normal
-- clients can read owned rows but cannot forge audit history or bypass scaling.
revoke insert, update, delete on public.foods, public.food_variants,
    public.diary_entries, public.nutrition_targets, public.audit_events from authenticated;
revoke update on public.profiles from authenticated;
grant update (timezone) on public.profiles to authenticated;

alter table public.food_variants drop constraint food_variants_supersedes_fk;
alter table public.food_variants add constraint food_variants_supersedes_owner_fk
    foreign key (supersedes_variant_id, owner_id) references public.food_variants(id, owner_id);
alter table public.food_variants add constraint food_variants_identity_unique unique(id, food_id, owner_id);
alter table public.diary_entries add constraint diary_variant_food_owner_fk
    foreign key (food_variant_id, food_id, owner_id) references public.food_variants(id, food_id, owner_id);
alter table public.diary_entries add constraint diary_variant_requires_food
    check (food_variant_id is null or food_id is not null);
create index foods_owner_idx on public.foods(owner_id);
create index variants_owner_idx on public.food_variants(owner_id);
create index variants_food_owner_idx on public.food_variants(food_id,owner_id);
create index variants_supersedes_owner_idx on public.food_variants(supersedes_variant_id,owner_id);
create index diary_owner_idx on public.diary_entries(owner_id);
create index diary_food_owner_idx on public.diary_entries(food_id,owner_id);
create index diary_variant_food_owner_idx on public.diary_entries(food_variant_id,food_id,owner_id);

create table private.action_groups (
    id uuid primary key,
    owner_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default clock_timestamp(),
    undone_at timestamptz
);
create index action_groups_owner_created on private.action_groups(owner_id, created_at desc);
alter table private.action_groups enable row level security;
create table private.target_confirmations (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references public.profiles(id) on delete cascade,
    proposed jsonb not null,
    expires_at timestamptz not null default now() + interval '5 minutes'
);
alter table private.target_confirmations enable row level security;
create index target_confirmations_owner_idx on private.target_confirmations(owner_id);
revoke all on private.action_groups, private.target_confirmations from public, anon, authenticated;

create function private.protect_diary_basis() returns trigger language plpgsql set search_path = '' as $$
begin
    if tg_op = 'UPDATE' and row(new.owner_id,new.food_id,new.food_variant_id,new.basis_amount_snapshot,
      new.basis_unit_snapshot,new.basis_calories_kcal_snapshot,new.basis_protein_g_snapshot,
      new.basis_fat_g_snapshot,new.basis_carbs_g_snapshot)
      is distinct from row(old.owner_id,old.food_id,old.food_variant_id,old.basis_amount_snapshot,
      old.basis_unit_snapshot,old.basis_calories_kcal_snapshot,old.basis_protein_g_snapshot,
      old.basis_fat_g_snapshot,old.basis_carbs_g_snapshot) then
        raise exception 'Nutritional basis is immutable' using errcode='23514';
    end if;
    if new.quantity_unit <> new.basis_unit_snapshot then
        raise exception 'Quantity unit must match nutritional basis' using errcode='23514';
    end if;
    new.calories_kcal_snapshot := round(new.basis_calories_kcal_snapshot * new.quantity / new.basis_amount_snapshot,3);
    new.protein_g_snapshot := round(new.basis_protein_g_snapshot * new.quantity / new.basis_amount_snapshot,3);
    new.fat_g_snapshot := round(new.basis_fat_g_snapshot * new.quantity / new.basis_amount_snapshot,3);
    new.carbs_g_snapshot := round(new.basis_carbs_g_snapshot * new.quantity / new.basis_amount_snapshot,3);
    new.updated_at := now();
    return new;
end; $$;
create trigger diary_basis_and_totals before insert or update on public.diary_entries
    for each row execute function private.protect_diary_basis();

create function private.check_timezone() returns trigger language plpgsql set search_path = '' as $$
begin
    if not exists(select 1 from pg_catalog.pg_timezone_names where name=new.timezone) then
        raise exception 'Invalid IANA timezone' using errcode='23514';
    end if;
    new.updated_at := now();
    return new;
end; $$;
create trigger profile_timezone before insert or update on public.profiles
    for each row execute function private.check_timezone();

create function private.nutrition_operation(p_operation text,p_input jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    uid uuid := auth.uid();
    action_id uuid := coalesce((p_input->>'action_id')::uuid,gen_random_uuid());
    actor text := coalesce(p_input->>'actor','user');
    food public.foods;
    variant public.food_variants;
    entry public.diary_entries;
    old_entry public.diary_entries;
    targets public.nutrition_targets;
    old_targets public.nutrition_targets;
    event public.audit_events;
    before_json jsonb;
    after_json jsonb;
    result jsonb;
    rows_json jsonb := '[]'::jsonb;
    source_id uuid;
    token uuid;
    zone text;
    consumed timestamptz;
    meal text;
    undo_id uuid;
    count_rows integer := 0;
begin
    if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if actor not in ('user','ai') then raise exception 'Invalid actor' using errcode='22023'; end if;
    -- Serialize each user's writes/undo so one undo cannot race a new action.
    perform 1 from public.profiles where id=uid for update;
    select timezone into strict zone from public.profiles where id=uid;
    if p_operation='prepare_targets' then
        insert into private.target_confirmations(owner_id,proposed)
        values(uid,p_input - 'action_id' - 'actor' - 'confirmation') returning id into token;
        return jsonb_build_object('ok',true,'action_id',null,'data',jsonb_build_object('confirmation',token),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation='undo_last_action' then
        select id into undo_id from private.action_groups where owner_id=uid and undone_at is null
            order by created_at desc,id desc limit 1 for update;
        if undo_id is null then raise exception 'No undoable action' using errcode='22023'; end if;
        for event in select * from public.audit_events where owner_id=uid and action_group_id=undo_id
            order by event_sequence desc loop
            if event.entity_type='diary_entries' then
                select * into strict entry from public.diary_entries where id=event.entity_id and owner_id=uid;
                before_json := to_jsonb(entry);
                if event.before_state is null then
                    update public.diary_entries set deleted_at=now() where id=entry.id returning * into entry;
                else
                    old_entry := jsonb_populate_record(null::public.diary_entries,event.before_state);
                    update public.diary_entries set quantity=old_entry.quantity,consumed_at=old_entry.consumed_at,
                        meal_type=old_entry.meal_type,deleted_at=old_entry.deleted_at
                        where id=entry.id returning * into entry;
                end if;
                after_json := to_jsonb(entry);
            elsif event.entity_type='food_variants' then
                select to_jsonb(v) into before_json from public.food_variants v where id=event.entity_id and owner_id=uid;
                update public.food_variants set archived_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(food_variants.*) into after_json;
            elsif event.entity_type='foods' then
                select to_jsonb(f) into before_json from public.foods f where id=event.entity_id and owner_id=uid;
                update public.foods set archived_at=now(),updated_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(foods.*) into after_json;
            elsif event.entity_type='nutrition_targets' then
                select to_jsonb(t) into before_json from public.nutrition_targets t where owner_id=uid;
                if event.before_state is null then
                    delete from public.nutrition_targets where owner_id=uid;
                    after_json := null;
                else
                    old_targets := jsonb_populate_record(null::public.nutrition_targets,event.before_state);
                    update public.nutrition_targets set daily_calories_kcal=old_targets.daily_calories_kcal,
                        daily_protein_g=old_targets.daily_protein_g,daily_fat_g=old_targets.daily_fat_g,
                        daily_carbs_g=old_targets.daily_carbs_g,adjustment_limit_ratio=old_targets.adjustment_limit_ratio,
                        updated_at=now() where owner_id=uid returning to_jsonb(nutrition_targets.*) into after_json;
                end if;
            else raise exception 'Unsupported audit entity'; end if;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
                values(uid,action_id,actor,'undo',event.entity_type,event.entity_id,before_json,after_json);
            count_rows := count_rows+1;
        end loop;
        update private.action_groups set undone_at=now() where id=undo_id;
        return jsonb_build_object('ok',true,'action_id',action_id,'data',jsonb_build_object('undone_action_id',undo_id,'count',count_rows),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation not in ('create_food','log_food','update_log','delete_log','repeat_meal','update_targets') then
        raise exception 'Unknown operation' using errcode='22023';
    end if;
    insert into private.action_groups(id,owner_id) values(action_id,uid) on conflict(id) do nothing;
    if not exists(select 1 from private.action_groups where id=action_id and owner_id=uid and undone_at is null) then
        raise exception 'Invalid action group' using errcode='42501';
    end if;
    if p_operation='create_food' then
        insert into public.foods(owner_id,name,normalized_name,kind,brand,source)
        values(uid,p_input->>'name',lower(trim(p_input->>'name')),coalesce(p_input->>'kind','generic'),p_input->>'brand',p_input->>'source')
        returning * into food;
        insert into public.food_variants(owner_id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
        values(uid,food.id,(p_input->>'serving_amount')::numeric,p_input->>'serving_unit',
            (p_input->>'calories_kcal')::numeric,(p_input->>'protein_g')::numeric,(p_input->>'fat_g')::numeric,
            (p_input->>'carbs_g')::numeric,coalesce((p_input->>'is_estimated')::boolean,false),p_input->>'source_note') returning * into variant;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state) values
            (uid,action_id,actor,'insert','foods',food.id,to_jsonb(food)),
            (uid,action_id,actor,'insert','food_variants',variant.id,to_jsonb(variant));
        result := jsonb_build_object('food',to_jsonb(food),'variant',to_jsonb(variant));
    elsif p_operation='log_food' then
        consumed := (p_input->>'consumed_at')::timestamptz;
        meal := coalesce(p_input->>'meal_type',case when extract(hour from consumed at time zone zone) between 5 and 11 then 'morning'
            when extract(hour from consumed at time zone zone) between 12 and 16 then 'day' else 'evening' end);
        if p_input->>'food_variant_id' is not null then
            select * into strict variant from public.food_variants where id=(p_input->>'food_variant_id')::uuid and owner_id=uid and archived_at is null;
            select * into strict food from public.foods where id=variant.food_id and owner_id=uid and archived_at is null;
            if p_input->>'quantity_unit' is not null and p_input->>'quantity_unit' <> variant.serving_unit then
                raise exception 'Quantity unit must match variant' using errcode='22023';
            end if;
            entry.food_id := food.id; entry.food_variant_id := variant.id; entry.snapshot_name := food.name; entry.snapshot_brand := food.brand;
            entry.basis_amount_snapshot := variant.serving_amount; entry.basis_unit_snapshot := variant.serving_unit;
            entry.basis_calories_kcal_snapshot := variant.calories_kcal; entry.basis_protein_g_snapshot := variant.protein_g;
            entry.basis_fat_g_snapshot := variant.fat_g; entry.basis_carbs_g_snapshot := variant.carbs_g;
        else
            entry.snapshot_name := p_input->>'snapshot_name'; entry.snapshot_brand := p_input->>'snapshot_brand';
            entry.basis_amount_snapshot := (p_input->>'quantity')::numeric; entry.basis_unit_snapshot := p_input->>'quantity_unit';
            entry.basis_calories_kcal_snapshot := (p_input->>'calories')::numeric; entry.basis_protein_g_snapshot := (p_input->>'protein_g')::numeric;
            entry.basis_fat_g_snapshot := (p_input->>'fat_g')::numeric; entry.basis_carbs_g_snapshot := (p_input->>'carbs_g')::numeric;
        end if;
        insert into public.diary_entries(owner_id,food_id,food_variant_id,meal_group_id,meal_type,snapshot_name,snapshot_brand,
            basis_amount_snapshot,basis_unit_snapshot,basis_calories_kcal_snapshot,basis_protein_g_snapshot,basis_fat_g_snapshot,basis_carbs_g_snapshot,
            quantity,quantity_unit,calories_kcal_snapshot,protein_g_snapshot,fat_g_snapshot,carbs_g_snapshot,confidence,consumed_at)
        values(uid,entry.food_id,entry.food_variant_id,(p_input->>'meal_group_id')::uuid,meal,entry.snapshot_name,entry.snapshot_brand,
            entry.basis_amount_snapshot,entry.basis_unit_snapshot,entry.basis_calories_kcal_snapshot,entry.basis_protein_g_snapshot,entry.basis_fat_g_snapshot,entry.basis_carbs_g_snapshot,
            (p_input->>'quantity')::numeric,entry.basis_unit_snapshot,0,0,0,0,(p_input->>'confidence')::numeric,consumed) returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
            values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation in ('update_log','delete_log') then
        select * into strict old_entry from public.diary_entries where id=(p_input->>'entry_id')::uuid and owner_id=uid and deleted_at is null for update;
        update public.diary_entries set
            quantity=coalesce((p_input->>'quantity')::numeric,old_entry.quantity),
            consumed_at=coalesce((p_input->>'consumed_at')::timestamptz,old_entry.consumed_at),
            meal_type=coalesce(p_input->>'meal_type',old_entry.meal_type),
            deleted_at=case when p_operation='delete_log' then now() else null end
            where id=old_entry.id returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,case when p_operation='delete_log' then 'soft_delete' else 'update' end,'diary_entries',entry.id,to_jsonb(old_entry),to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation='repeat_meal' then
        consumed := (p_input->>'consumed_at')::timestamptz;
        for source_id in select value::uuid from jsonb_array_elements_text(p_input->'entry_ids') loop
            select * into strict entry from public.diary_entries where id=source_id and owner_id=uid and deleted_at is null;
            entry.id := gen_random_uuid(); entry.created_at := now(); entry.updated_at := now(); entry.consumed_at := consumed;
            entry.meal_group_id := action_id; entry.meal_type := coalesce(p_input->>'meal_type',entry.meal_type);
            insert into public.diary_entries select (entry).* returning * into entry;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
                values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
            rows_json := rows_json || jsonb_build_array(to_jsonb(entry)); count_rows := count_rows+1;
        end loop;
        if count_rows=0 then raise exception 'Source entries required' using errcode='22023'; end if;
        result := rows_json;
    elsif p_operation='update_targets' then
        delete from private.target_confirmations where id=(p_input->>'confirmation')::uuid and owner_id=uid
            and expires_at>now() and proposed=p_input - 'action_id' - 'actor' - 'confirmation' returning id into token;
        if token is null then raise exception 'Explicit target confirmation required' using errcode='42501'; end if;
        select * into old_targets from public.nutrition_targets where owner_id=uid;
        insert into public.nutrition_targets(owner_id,daily_calories_kcal,daily_protein_g,daily_fat_g,daily_carbs_g,adjustment_limit_ratio)
        values(uid,(p_input->>'daily_calories_kcal')::numeric,(p_input->>'daily_protein_g')::numeric,(p_input->>'daily_fat_g')::numeric,
            (p_input->>'daily_carbs_g')::numeric,(p_input->>'adjustment_limit_ratio')::numeric)
        on conflict(owner_id) do update set daily_calories_kcal=excluded.daily_calories_kcal,daily_protein_g=excluded.daily_protein_g,
            daily_fat_g=excluded.daily_fat_g,daily_carbs_g=excluded.daily_carbs_g,adjustment_limit_ratio=excluded.adjustment_limit_ratio,updated_at=now()
        returning * into targets;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,'target_change','nutrition_targets',uid,case when old_targets.owner_id is null then null else to_jsonb(old_targets) end,to_jsonb(targets));
        result := to_jsonb(targets);
    end if;
    return jsonb_build_object('ok',true,'action_id',action_id,'data',result,'warnings','[]'::jsonb,'undoable',true);
exception when no_data_found then
    raise exception 'Owned item is unavailable' using errcode='22023';
end; $$;

-- Public SECURITY INVOKER wrappers expose only the typed operation names.
create function public.jetmeal_create_food(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('create_food',p_input) $$;
create function public.jetmeal_log_food(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('log_food',p_input) $$;
create function public.jetmeal_update_log(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('update_log',p_input) $$;
create function public.jetmeal_delete_log(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('delete_log',p_input) $$;
create function public.jetmeal_repeat_meal(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('repeat_meal',p_input) $$;
create function public.jetmeal_undo_last_action(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('undo_last_action',p_input) $$;
create function public.jetmeal_prepare_targets(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('prepare_targets',p_input) $$;
create function public.jetmeal_update_targets(p_input jsonb) returns jsonb language sql security invoker set search_path='' as $$ select private.nutrition_operation('update_targets',p_input) $$;

revoke all on function private.handle_new_user(),private.protect_diary_basis(),private.check_timezone(),
    private.nutrition_operation(text,jsonb) from public,anon,authenticated;
grant usage on schema private to authenticated;
grant execute on function private.nutrition_operation(text,jsonb) to authenticated;
revoke all on function public.jetmeal_create_food(jsonb),public.jetmeal_log_food(jsonb),public.jetmeal_update_log(jsonb),
    public.jetmeal_delete_log(jsonb),public.jetmeal_repeat_meal(jsonb),public.jetmeal_undo_last_action(jsonb),
    public.jetmeal_prepare_targets(jsonb),public.jetmeal_update_targets(jsonb) from public,anon;
grant execute on function public.jetmeal_create_food(jsonb),public.jetmeal_log_food(jsonb),public.jetmeal_update_log(jsonb),
    public.jetmeal_delete_log(jsonb),public.jetmeal_repeat_meal(jsonb),public.jetmeal_undo_last_action(jsonb),
    public.jetmeal_prepare_targets(jsonb),public.jetmeal_update_targets(jsonb) to authenticated;
commit;

begin;
-- Estimation status belongs to the immutable history, including estimates with
-- no numeric confidence. Existing catalogue links remain owner-protected.
alter table public.diary_entries add column is_estimated_snapshot boolean;
update public.diary_entries d set is_estimated_snapshot=case
    when d.food_variant_id is null then true
    else coalesce((select v.is_estimated from public.food_variants v
        where v.id=d.food_variant_id and v.owner_id=d.owner_id),false) end;
alter table public.diary_entries alter column is_estimated_snapshot set not null;

create function private.protect_estimate_snapshot() returns trigger
language plpgsql set search_path='' as $$
begin
    if tg_op='UPDATE' and new.is_estimated_snapshot is distinct from old.is_estimated_snapshot then
        raise exception 'Estimate snapshot is immutable' using errcode='23514';
    end if;
    if tg_op='INSERT' and new.is_estimated_snapshot is null then
        if new.food_variant_id is null then
            new.is_estimated_snapshot := true;
        else
            select v.is_estimated into new.is_estimated_snapshot from public.food_variants v
                where v.id=new.food_variant_id and v.owner_id=new.owner_id;
        end if;
    end if;
    return new;
end; $$;
create trigger diary_estimate_snapshot before insert or update on public.diary_entries
    for each row execute function private.protect_estimate_snapshot();
revoke all on function private.protect_estimate_snapshot() from public,anon,authenticated;
commit;

begin;
-- Auth's INSERT trigger covers future registrations; existing users predate it.
-- Do not infer a timezone or nutrition targets from user metadata.
insert into public.profiles(id)
    select id from auth.users
    on conflict(id) do nothing;
commit;

begin;
-- Additive import of explicitly owned, complete source records only.
-- Originals, ownerless catalogues/history, incomplete nutrition and legacy
-- business functions/policies remain untouched. Source IDs are deterministic.
do $$
declare
    legacy record;
    source_json jsonb;
    item public.foods;
    variant public.food_variants;
    entry public.diary_entries;
    targets public.nutrition_targets;
    owner uuid;
    source_id uuid;
    linked_food uuid;
    amount numeric;
    kcal numeric;
    protein numeric;
    fat numeric;
    carbs numeric;
    status text;
    meal text;
    import_action uuid := gen_random_uuid();
begin
    if to_regclass('public.products') is not null then
        for legacy in select to_jsonb(p) as data from public.products p loop
            source_json := legacy.data;
            owner := (source_json->>'user_id')::uuid;
            source_id := (source_json->>'id')::uuid;
            kcal := (source_json->>'kcal_per_100g')::numeric;
            protein := (source_json->>'protein_g_per_100g')::numeric;
            fat := (source_json->>'fat_g_per_100g')::numeric;
            carbs := (source_json->>'carbs_g_per_100g')::numeric;
            status := source_json->>'nutrition_status';
            if owner is null or not exists(select 1 from public.profiles where id=owner)
                or coalesce(length(trim(source_json->>'name')),0)=0
                or status not in ('verified','estimated') or status is null
                or kcal is null or not(kcal between 0 and 1000000)
                or protein is null or not(protein between 0 and 1000000)
                or fat is null or not(fat between 0 and 1000000)
                or carbs is null or not(carbs between 0 and 1000000) then continue; end if;
            insert into public.foods(id,owner_id,name,normalized_name,kind,brand,source,created_at,updated_at,archived_at)
            values(source_id,owner,source_json->>'name',lower(trim(source_json->>'name')),
                case when source_json->>'source_type' in ('generic','packaged','restaurant','canteen','ai_estimate')
                    then source_json->>'source_type' else 'generic' end,
                source_json->>'brand','legacy.products:' || coalesce(source_json->>'source','unknown'),
                (source_json->>'created_at')::timestamptz,(source_json->>'updated_at')::timestamptz,
                case when (source_json->>'active')::boolean=false then (source_json->>'updated_at')::timestamptz end)
            on conflict(id) do nothing returning * into item;
            if not found then continue; end if;
            insert into public.food_variants(id,owner_id,food_id,serving_amount,serving_unit,
                calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note,created_at,archived_at)
            values(source_id,owner,item.id,100,'g',kcal,protein,fat,carbs,status='estimated',
                jsonb_build_object('legacy_table','products','legacy_row',source_json)::text,
                (source_json->>'created_at')::timestamptz,item.archived_at) returning * into variant;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state) values
                (owner,import_action,'system','insert','foods',item.id,to_jsonb(item)),
                (owner,import_action,'system','insert','food_variants',variant.id,to_jsonb(variant));
        end loop;
    end if;
    if to_regclass('public.meal_log') is not null then
        for legacy in select to_jsonb(m) as data from public.meal_log m loop
            source_json := legacy.data;
            owner := (source_json->>'user_id')::uuid;
            source_id := (source_json->>'id')::uuid;
            amount := (source_json->>'grams')::numeric;
            kcal := (source_json->>'calories')::numeric;
            protein := (source_json->>'protein_g')::numeric;
            fat := (source_json->>'fat_g')::numeric;
            carbs := (source_json->>'carbs_g')::numeric;
            meal := case source_json->>'meal_type' when 'breakfast' then 'morning' when 'lunch' then 'day'
                when 'dinner' then 'evening' when 'snack' then 'snack' else null end;
            if owner is null or not exists(select 1 from public.profiles where id=owner)
                or coalesce(length(trim(source_json->>'description')),0)=0 or meal is null
                or source_json->>'logged_at' is null
                or amount is null or not(amount>0 and amount<=1000000)
                or kcal is null or not(kcal between 0 and 1000000)
                or protein is null or not(protein between 0 and 1000000)
                or fat is null or not(fat between 0 and 1000000)
                or carbs is null or not(carbs between 0 and 1000000) then continue; end if;
            select id into linked_food from public.foods where id=(source_json->>'product_id')::uuid and owner_id=owner;
            -- Preserve the old diary's own stored totals, never recompute old
            -- consumption from a current product's nutritional values.
            insert into public.diary_entries(id,owner_id,food_id,meal_type,snapshot_name,
                basis_amount_snapshot,basis_unit_snapshot,basis_calories_kcal_snapshot,basis_protein_g_snapshot,
                basis_fat_g_snapshot,basis_carbs_g_snapshot,quantity,quantity_unit,calories_kcal_snapshot,
                protein_g_snapshot,fat_g_snapshot,carbs_g_snapshot,consumed_at,created_at,is_estimated_snapshot)
            values(source_id,owner,linked_food,meal,source_json->>'description',amount,'g',kcal,protein,fat,carbs,
                amount,'g',kcal,protein,fat,carbs,(source_json->>'logged_at')::timestamptz,
                (source_json->>'created_at')::timestamptz,
                case when source_json->>'source' in ('official_pdf','label','menu') then false
                    when linked_food is not null then (select v.is_estimated from public.food_variants v where v.id=linked_food and v.owner_id=owner)
                    else true end)
            on conflict(id) do nothing returning * into entry;
            if not found then continue; end if;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
                values(owner,import_action,'system','insert','diary_entries',entry.id,
                    to_jsonb(entry) || jsonb_build_object('legacy_table','meal_log','legacy_row',source_json));
        end loop;
    end if;
    if to_regclass('public.daily_targets') is not null then
        -- Latest effective record per explicit owner. Never fall back to an
        -- older complete target when the current record is incomplete.
        for legacy in select distinct on(t.user_id) to_jsonb(t) as data from public.daily_targets t
            where t.user_id is not null and t.effective_from<=current_date
            order by t.user_id,t.effective_from desc,t.created_at desc,t.id desc loop
            source_json := legacy.data;
            owner := (source_json->>'user_id')::uuid;
            kcal := (source_json->>'calorie_intake_target')::numeric;
            protein := (source_json->>'protein_target_g')::numeric;
            fat := (source_json->>'fat_target_g')::numeric;
            carbs := (source_json->>'carbs_target_g')::numeric;
            if not exists(select 1 from public.profiles where id=owner)
                or kcal is null or not(kcal>0 and kcal<=1000000)
                or protein is null or not(protein between 0 and 1000000)
                or fat is null or not(fat between 0 and 1000000)
                or carbs is null or not(carbs between 0 and 1000000) then continue; end if;
            insert into public.nutrition_targets(owner_id,daily_calories_kcal,daily_protein_g,daily_fat_g,daily_carbs_g,adjustment_limit_ratio)
            values(owner,kcal,protein,fat,carbs,0.10) on conflict(owner_id) do nothing returning * into targets;
            if not found then continue; end if;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
                values(owner,import_action,'system','target_change','nutrition_targets',owner,
                    to_jsonb(targets) || jsonb_build_object('legacy_table','daily_targets','legacy_row',source_json));
        end loop;
    end if;
end $$;
commit;

begin;
-- Hosted projects may permit anonymous Auth even though JetMeal does not.
-- is_anonymous is a trusted top-level JWT claim, never user_metadata.
create function private.registered_uid() returns uuid
language plpgsql security invoker set search_path='' as $$
declare uid uuid := auth.uid();
begin
    if uid is null or coalesce(auth.jwt()->>'is_anonymous','false')<>'false' then
        raise exception 'A registered account is required' using errcode='42501';
    end if;
    return uid;
end; $$;
revoke all on function private.registered_uid() from public,anon,authenticated;

-- Replace only the identity initializer of our own dispatcher; retain its
-- reviewed mutation/audit implementation and every existing privilege.
do $$
declare definition text;
begin
    select pg_get_functiondef('private.nutrition_operation(text,jsonb)'::regprocedure) into definition;
    if strpos(definition,'uid uuid := auth.uid();')=0 then
        raise exception 'Unexpected JetMeal dispatcher definition';
    end if;
    execute replace(definition,'uid uuid := auth.uid();','uid uuid := private.registered_uid();');
end $$;

-- Scope is exactly the six canonical tables; no legacy RLS policy is changed.
do $$
declare item record;
    predicate text;
begin
    for item in select tablename,policyname,cmd from pg_policies where schemaname='public'
        and tablename in ('profiles','foods','food_variants','diary_entries','nutrition_targets','audit_events') loop
        predicate := format('(select auth.uid()) = %I and coalesce((select auth.jwt()->>''is_anonymous''),''false'') = ''false''',
            case when item.tablename='profiles' then 'id' else 'owner_id' end);
        if item.cmd in ('SELECT','UPDATE') then
            execute format('alter policy %I on public.%I using (%s)',item.policyname,item.tablename,predicate);
        end if;
        if item.cmd in ('INSERT','UPDATE') then
            execute format('alter policy %I on public.%I with check (%s)',item.policyname,item.tablename,predicate);
        end if;
    end loop;
end $$;
commit;

begin;
-- Cover the actual (variant, owner) FK independently of the three-column
-- variant/food/owner index. meal_group_id is a grouping value, not a FK.
create index diary_variant_owner_idx on public.diary_entries(food_variant_id,owner_id);
do $$
declare item record;
    predicate text;
begin
    for item in select tablename,policyname,cmd from pg_policies where schemaname='public'
        and tablename in ('profiles','foods','food_variants','diary_entries','nutrition_targets','audit_events') loop
        -- Cache the complete auth.jwt() result in a scalar InitPlan, then
        -- extract its trusted claim outside that subquery.
        predicate := format('(select auth.uid()) = %I and coalesce((select auth.jwt())->>''is_anonymous'',''false'') = ''false''',
            case when item.tablename='profiles' then 'id' else 'owner_id' end);
        if item.cmd in ('SELECT','UPDATE') then
            execute format('alter policy %I on public.%I using (%s)',item.policyname,item.tablename,predicate);
        end if;
        if item.cmd in ('INSERT','UPDATE') then
            execute format('alter policy %I on public.%I with check (%s)',item.policyname,item.tablename,predicate);
        end if;
    end loop;
end $$;
commit;

-- The fresh-project bootstrap also installs the explicit zero-day extension.
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

-- Additive history and weight tables, used for fresh installations too.
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

-- Durable offline write acknowledgements and optimistic concurrency.
-- JetMeal offline mutations: exactly-once server execution for at-least-once delivery.
-- Receipts are private and permanently associated with a registered auth user.
create table if not exists private.offline_mutation_receipts (
    owner_id uuid not null references public.profiles(id) on delete cascade,
    request_id uuid not null,
    request_hash text not null,
    response jsonb not null,
    created_at timestamptz not null default now(),
    primary key(owner_id, request_id)
);
alter table private.offline_mutation_receipts enable row level security;
revoke all on private.offline_mutation_receipts from public, anon, authenticated;

-- Modification conflicts are checked against a genuine revision timestamp.
-- Existing food operations update this column through the same trigger.
create or replace function private.touch_diary_updated_at()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
revoke all on function private.touch_diary_updated_at() from public, anon, authenticated;
create trigger diary_touch_updated_at before update on public.diary_entries
    for each row execute function private.touch_diary_updated_at();

create or replace function private.apply_offline_mutation(
    p_request_id uuid,
    p_operation text,
    p_input jsonb,
    p_expected_updated_at timestamptz
) returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := (select auth.uid());
    stamp text;
    stored private.offline_mutation_receipts;
    response jsonb;
    current_revision timestamptz;
begin
    if uid is null or coalesce((select auth.jwt()->>'is_anonymous')::boolean,false) then
        raise exception 'Registered user required' using errcode='42501';
    end if;
    if p_request_id is null or p_operation not in ('log_food','update_log','delete_log')
        or p_input is null or jsonb_typeof(p_input) <> 'object' then
        raise exception 'Invalid offline request' using errcode='22023';
    end if;
    if p_input ? 'actor' or p_input ? 'action_id' then
        raise exception 'Offline action identity must be generated on server' using errcode='22023';
    end if;
    if (p_operation='log_food' and p_expected_updated_at is not null)
        or (p_operation in ('update_log','delete_log') and p_expected_updated_at is null) then
        raise exception 'Invalid offline edit version' using errcode='22023';
    end if;
    -- Serialize with the ordinary nutrition_operation lock; duplicate retries
    -- see a persisted receipt rather than running an operation twice.
    perform 1 from public.profiles where id=uid for update;
    stamp := md5(p_operation || ':' || p_input::text || ':' ||
        coalesce(p_expected_updated_at::text,''));
    select * into stored from private.offline_mutation_receipts
        where owner_id=uid and request_id=p_request_id for update;
    if found then
        if stored.request_hash <> stamp then
            raise exception 'Offline request ID was reused with different contents' using errcode='22023';
        end if;
        return stored.response;
    end if;
    if p_operation in ('update_log','delete_log') then
        select updated_at into current_revision from public.diary_entries
        where id=(p_input->>'entry_id')::uuid and owner_id=uid and deleted_at is null;
        if current_revision is null or current_revision <> p_expected_updated_at then
            raise exception 'offline_stale_entry' using errcode='23514';
        end if;
    end if;
    response := private.nutrition_operation(p_operation,
        p_input || jsonb_build_object('action_id',p_request_id::text) ||
        case when p_operation='log_food' then
            jsonb_build_object('meal_group_id',p_request_id::text)
        else '{}'::jsonb end);
    insert into private.offline_mutation_receipts(owner_id,request_id,request_hash,response)
    values(uid,p_request_id,stamp,response);
    return response;
end;
$$;
revoke all on function private.apply_offline_mutation(uuid,text,jsonb,timestamptz)
    from public, anon, authenticated;
grant execute on function private.apply_offline_mutation(uuid,text,jsonb,timestamptz)
    to authenticated;

-- The exposed public endpoint is SECURITY INVOKER; privileged receipt storage
-- is reachable only through the authenticated, owner-bound private function.
create or replace function public.jetmeal_sync_mutation(
    p_request_id uuid,
    p_operation text,
    p_input jsonb,
    p_expected_updated_at timestamptz default null
) returns jsonb language sql security invoker set search_path = ''
as $replay$
    select private.apply_offline_mutation(p_request_id,p_operation,p_input,p_expected_updated_at)
$replay$;
revoke all on function public.jetmeal_sync_mutation(uuid,text,jsonb,timestamptz)
    from public, anon;
grant execute on function public.jetmeal_sync_mutation(uuid,text,jsonb,timestamptz)
    to authenticated;

-- Typed per-food measures; preserves historical nutritional basis.
begin;
-- Optional food-specific measures. Base unit in food_variants remains authoritative.
create table if not exists public.food_measures (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  food_variant_id uuid not null,
  measure_key text not null check (measure_key in ('piece','serving','egg_medium','egg_large','tsp','tbsp','slice','package','cup')),
  label text not null check (char_length(trim(label)) between 1 and 32),
  base_amount numeric(10,3) not null check (base_amount>0 and base_amount<=1000000),
  is_approximate boolean not null default false,
  is_default boolean not null default false,
  source_note text,
  created_at timestamptz not null default now(),
  archived_at timestamptz,
  constraint food_measures_variant_owner_fk foreign key (food_variant_id,owner_id)
     references public.food_variants(id,owner_id) on delete restrict,
  unique (id,owner_id)
);
create unique index if not exists food_measures_variant_active_key
 on public.food_measures(owner_id,food_variant_id,measure_key) where archived_at is null;
create index if not exists food_measures_variant_idx
 on public.food_measures(owner_id,food_variant_id) where archived_at is null;
alter table public.food_measures enable row level security;
revoke all on public.food_measures from public, anon, authenticated;
grant select on public.food_measures to authenticated;
create policy food_measures_select_own on public.food_measures for select to authenticated
 using ((select auth.uid())=owner_id);

-- Original entered measure is an immutable-time snapshot even if its definition is archived.
alter table public.diary_entries
 add column if not exists entered_measure_label text,
 add column if not exists entered_measure_key text,
 add column if not exists entered_measure_quantity numeric(10,3),
 add column if not exists entered_measure_base_amount numeric(10,3),
 add column if not exists entered_measure_approximate boolean;
alter table public.diary_entries
 add constraint diary_entered_measure_consistent check (
   (entered_measure_label is null and entered_measure_key is null
    and entered_measure_quantity is null and entered_measure_base_amount is null
    and entered_measure_approximate is null)
   or
   (entered_measure_label is not null and entered_measure_key is not null
    and entered_measure_quantity>0 and entered_measure_base_amount>0
    and entered_measure_approximate is not null)
 );

-- Conservative automatic measures. Never pretend a 100 g nutrition reference is a whole burger.
create or replace function private.seed_variant_measures() returns trigger
 language plpgsql security definer set search_path='' as $seed$
declare
  item public.foods;
begin
  select * into item from public.foods where id=new.food_id and owner_id=new.owner_id;
  if new.serving_unit='g' and item.kind='restaurant'
    and new.source_note like '%serving=1 порция%' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,source_note)
    values(new.owner_id,new.id,
      case when item.name ~* '(бургер|маффин)' then 'piece' else 'serving' end,
      case when item.name ~* '(бургер|маффин)' then 'шт.' else 'порция' end,
      new.serving_amount,true,'Официальная порция из источника продукта')
    on conflict do nothing;
  end if;
  if new.serving_unit='g' and item.name ~* '^яйцо( куриное)?( сырое)?$' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,is_approximate,source_note) values
      (new.owner_id,new.id,'egg_medium','Среднее',44,false,true,
       'Приблизительно 44 г съедобной части'),
      (new.owner_id,new.id,'egg_large','Большое',50,true,true,
       'Приблизительно 50 г съедобной части')
    on conflict do nothing;
  end if;
  if new.serving_unit='g' and item.name ~* '^сахар( белый| песок)?$' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,is_approximate,source_note) values
      (new.owner_id,new.id,'tsp','ч. л.',4,false,true,'Ровная чайная ложка, приблизительно'),
      (new.owner_id,new.id,'tbsp','ст. л.',12,false,true,'Ровная столовая ложка, приблизительно')
    on conflict do nothing;
  end if;
  return new;
end $seed$;
revoke all on function private.seed_variant_measures() from public,anon,authenticated;
create trigger food_variant_seed_measures after insert on public.food_variants
 for each row execute function private.seed_variant_measures();

-- Existing official portions are backfilled with the same conservative rule.
insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,source_note)
select v.owner_id,v.id,
 case when f.name ~* '(бургер|маффин)' then 'piece' else 'serving' end,
 case when f.name ~* '(бургер|маффин)' then 'шт.' else 'порция' end,
 v.serving_amount,true,'Официальная порция из источника продукта'
from public.food_variants v join public.foods f
 on f.id=v.food_id and f.owner_id=v.owner_id
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.kind='restaurant' and v.source_note like '%serving=1 порция%'
on conflict do nothing;

insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,is_approximate,source_note)
select v.owner_id,v.id,m.key,m.label,m.grams,m.def,true,m.source
from public.food_variants v join public.foods f on f.id=v.food_id and f.owner_id=v.owner_id
cross join (values ('egg_medium','Среднее',44.0,false,'Примерно 44 г съедобной части'),
                   ('egg_large','Большое',50.0,true,'Примерно 50 г съедобной части'))
 as m(key,label,grams,def,source)
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.name ~* '^яйцо( куриное)?( сырое)?$'
on conflict do nothing;
insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,is_approximate,source_note)
select v.owner_id,v.id,m.key,m.label,m.grams,false,true,m.source
from public.food_variants v join public.foods f on f.id=v.food_id and f.owner_id=v.owner_id
cross join (values ('tsp','ч. л.',4.0,'Примерно 4 г сахара'),
                   ('tbsp','ст. л.',12.0,'Примерно 12 г сахара'))
 as m(key,label,grams,source)
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.name ~* '^сахар( белый| песок)?$'
on conflict do nothing;

create or replace function private.nutrition_operation(p_operation text,p_input jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    uid uuid := auth.uid();
    action_id uuid := coalesce((p_input->>'action_id')::uuid,gen_random_uuid());
    actor text := coalesce(p_input->>'actor','user');
    food public.foods;
    variant public.food_variants;
    entry public.diary_entries;
    old_entry public.diary_entries;
    targets public.nutrition_targets;
    old_targets public.nutrition_targets;
    event public.audit_events;
    before_json jsonb;
    after_json jsonb;
    result jsonb;
    rows_json jsonb := '[]'::jsonb;
    source_id uuid;
    token uuid;
    zone text;
    consumed timestamptz;
    meal text;
    undo_id uuid;
    count_rows integer := 0;
    measure public.food_measures;
    measured_quantity numeric;
    resolved_amount numeric;
begin
    if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if actor not in ('user','ai') then raise exception 'Invalid actor' using errcode='22023'; end if;
    -- Serialize each user's writes/undo so one undo cannot race a new action.
    perform 1 from public.profiles where id=uid for update;
    select timezone into strict zone from public.profiles where id=uid;
    if p_operation='prepare_targets' then
        insert into private.target_confirmations(owner_id,proposed)
        values(uid,p_input - 'action_id' - 'actor' - 'confirmation') returning id into token;
        return jsonb_build_object('ok',true,'action_id',null,'data',jsonb_build_object('confirmation',token),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation='undo_last_action' then
        select id into undo_id from private.action_groups where owner_id=uid and undone_at is null
            order by created_at desc,id desc limit 1 for update;
        if undo_id is null then raise exception 'No undoable action' using errcode='22023'; end if;
        for event in select * from public.audit_events where owner_id=uid and action_group_id=undo_id
            order by event_sequence desc loop
            if event.entity_type='diary_entries' then
                select * into strict entry from public.diary_entries where id=event.entity_id and owner_id=uid;
                before_json := to_jsonb(entry);
                if event.before_state is null then
                    update public.diary_entries set deleted_at=now() where id=entry.id returning * into entry;
                else
                    old_entry := jsonb_populate_record(null::public.diary_entries,event.before_state);
                    update public.diary_entries set quantity=old_entry.quantity,consumed_at=old_entry.consumed_at,
                        meal_type=old_entry.meal_type,deleted_at=old_entry.deleted_at,
                        entered_measure_label=old_entry.entered_measure_label,
                        entered_measure_key=old_entry.entered_measure_key,
                        entered_measure_quantity=old_entry.entered_measure_quantity,
                        entered_measure_base_amount=old_entry.entered_measure_base_amount,
                        entered_measure_approximate=old_entry.entered_measure_approximate
                        where id=entry.id returning * into entry;
                end if;
                after_json := to_jsonb(entry);
            elsif event.entity_type='food_variants' then
                select to_jsonb(v) into before_json from public.food_variants v where id=event.entity_id and owner_id=uid;
                update public.food_variants set archived_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(food_variants.*) into after_json;
            elsif event.entity_type='foods' then
                select to_jsonb(f) into before_json from public.foods f where id=event.entity_id and owner_id=uid;
                update public.foods set archived_at=now(),updated_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(foods.*) into after_json;
            elsif event.entity_type='nutrition_targets' then
                select to_jsonb(t) into before_json from public.nutrition_targets t where owner_id=uid;
                if event.before_state is null then
                    delete from public.nutrition_targets where owner_id=uid;
                    after_json := null;
                else
                    old_targets := jsonb_populate_record(null::public.nutrition_targets,event.before_state);
                    update public.nutrition_targets set daily_calories_kcal=old_targets.daily_calories_kcal,
                        daily_protein_g=old_targets.daily_protein_g,daily_fat_g=old_targets.daily_fat_g,
                        daily_carbs_g=old_targets.daily_carbs_g,adjustment_limit_ratio=old_targets.adjustment_limit_ratio,
                        updated_at=now() where owner_id=uid returning to_jsonb(nutrition_targets.*) into after_json;
                end if;
            else raise exception 'Unsupported audit entity'; end if;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
                values(uid,action_id,actor,'undo',event.entity_type,event.entity_id,before_json,after_json);
            count_rows := count_rows+1;
        end loop;
        update private.action_groups set undone_at=now() where id=undo_id;
        return jsonb_build_object('ok',true,'action_id',action_id,'data',jsonb_build_object('undone_action_id',undo_id,'count',count_rows),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation not in ('create_food','log_food','update_log','delete_log','repeat_meal','update_targets') then
        raise exception 'Unknown operation' using errcode='22023';
    end if;
    insert into private.action_groups(id,owner_id) values(action_id,uid) on conflict(id) do nothing;
    if not exists(select 1 from private.action_groups where id=action_id and owner_id=uid and undone_at is null) then
        raise exception 'Invalid action group' using errcode='42501';
    end if;
    if p_operation='create_food' then
        insert into public.foods(owner_id,name,normalized_name,kind,brand,source)
        values(uid,p_input->>'name',lower(trim(p_input->>'name')),coalesce(p_input->>'kind','generic'),p_input->>'brand',p_input->>'source')
        returning * into food;
        insert into public.food_variants(owner_id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
        values(uid,food.id,(p_input->>'serving_amount')::numeric,p_input->>'serving_unit',
            (p_input->>'calories_kcal')::numeric,(p_input->>'protein_g')::numeric,(p_input->>'fat_g')::numeric,
            (p_input->>'carbs_g')::numeric,coalesce((p_input->>'is_estimated')::boolean,false),p_input->>'source_note') returning * into variant;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state) values
            (uid,action_id,actor,'insert','foods',food.id,to_jsonb(food)),
            (uid,action_id,actor,'insert','food_variants',variant.id,to_jsonb(variant));
        result := jsonb_build_object('food',to_jsonb(food),'variant',to_jsonb(variant));
    elsif p_operation='log_food' then
        if p_input ? 'measure_quantity' and not p_input ? 'measure_id' then
            raise exception 'Measure identifier required' using errcode='22023';
        end if;
        consumed := (p_input->>'consumed_at')::timestamptz;
        meal := coalesce(p_input->>'meal_type',case when extract(hour from consumed at time zone zone) between 5 and 11 then 'morning'
            when extract(hour from consumed at time zone zone) between 12 and 16 then 'day' else 'evening' end);
        if p_input->>'food_variant_id' is not null then
            select * into strict variant from public.food_variants where id=(p_input->>'food_variant_id')::uuid and owner_id=uid and archived_at is null;
            select * into strict food from public.foods where id=variant.food_id and owner_id=uid and archived_at is null;
            if p_input->>'quantity_unit' is not null and p_input->>'quantity_unit' <> variant.serving_unit then
                raise exception 'Quantity unit must match variant' using errcode='22023';
            end if;
            entry.food_id := food.id; entry.food_variant_id := variant.id; entry.snapshot_name := food.name; entry.snapshot_brand := food.brand;
            entry.basis_amount_snapshot := variant.serving_amount; entry.basis_unit_snapshot := variant.serving_unit;
            entry.basis_calories_kcal_snapshot := variant.calories_kcal; entry.basis_protein_g_snapshot := variant.protein_g;
            entry.basis_fat_g_snapshot := variant.fat_g; entry.basis_carbs_g_snapshot := variant.carbs_g;
        else
            entry.snapshot_name := p_input->>'snapshot_name'; entry.snapshot_brand := p_input->>'snapshot_brand';
            entry.basis_amount_snapshot := (p_input->>'quantity')::numeric; entry.basis_unit_snapshot := p_input->>'quantity_unit';
            entry.basis_calories_kcal_snapshot := (p_input->>'calories')::numeric; entry.basis_protein_g_snapshot := (p_input->>'protein_g')::numeric;
            entry.basis_fat_g_snapshot := (p_input->>'fat_g')::numeric; entry.basis_carbs_g_snapshot := (p_input->>'carbs_g')::numeric;
        end if;
        resolved_amount := (p_input->>'quantity')::numeric;
        if p_input ? 'measure_id' then
            if entry.food_variant_id is null then
                raise exception 'Measures require a catalogue variant' using errcode='22023';
            end if;
            select * into measure from public.food_measures
                where id=(p_input->>'measure_id')::uuid and owner_id=uid
                  and food_variant_id=entry.food_variant_id and archived_at is null;
            if not found then raise exception 'Unavailable serving measure' using errcode='22023'; end if;
            measured_quantity := (p_input->>'measure_quantity')::numeric;
            if measured_quantity is null or measured_quantity<=0 or measured_quantity>100000
                then raise exception 'Invalid serving count' using errcode='22023'; end if;
            resolved_amount := round(measured_quantity * measure.base_amount,3);
            if resolved_amount<=0 or resolved_amount>1000000
                then raise exception 'Invalid resolved amount' using errcode='22023'; end if;
            entry.entered_measure_label := measure.label;
            entry.entered_measure_key := measure.measure_key;
            entry.entered_measure_quantity := measured_quantity;
            entry.entered_measure_base_amount := measure.base_amount;
            entry.entered_measure_approximate := measure.is_approximate;
        end if;
        insert into public.diary_entries(owner_id,food_id,food_variant_id,meal_group_id,meal_type,snapshot_name,snapshot_brand,
            basis_amount_snapshot,basis_unit_snapshot,basis_calories_kcal_snapshot,basis_protein_g_snapshot,basis_fat_g_snapshot,basis_carbs_g_snapshot,
            quantity,quantity_unit,calories_kcal_snapshot,protein_g_snapshot,fat_g_snapshot,carbs_g_snapshot,confidence,consumed_at,
            entered_measure_label,entered_measure_key,entered_measure_quantity,
            entered_measure_base_amount,entered_measure_approximate)
        values(uid,entry.food_id,entry.food_variant_id,(p_input->>'meal_group_id')::uuid,meal,entry.snapshot_name,entry.snapshot_brand,
            entry.basis_amount_snapshot,entry.basis_unit_snapshot,entry.basis_calories_kcal_snapshot,entry.basis_protein_g_snapshot,entry.basis_fat_g_snapshot,entry.basis_carbs_g_snapshot,
            resolved_amount,entry.basis_unit_snapshot,0,0,0,0,(p_input->>'confidence')::numeric,consumed,
            entry.entered_measure_label,entry.entered_measure_key,entry.entered_measure_quantity,
            entry.entered_measure_base_amount,entry.entered_measure_approximate) returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
            values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation in ('update_log','delete_log') then
        select * into strict old_entry from public.diary_entries where id=(p_input->>'entry_id')::uuid and owner_id=uid and deleted_at is null for update;
        if p_operation='update_log' and p_input ? 'measure_quantity' and not p_input ? 'measure_id' then
            raise exception 'Measure identifier required' using errcode='22023';
        end if;
        resolved_amount := coalesce((p_input->>'quantity')::numeric,old_entry.quantity);
        if p_operation='update_log' and p_input ? 'measure_id' then
            select * into measure from public.food_measures
              where id=(p_input->>'measure_id')::uuid and owner_id=uid
                and food_variant_id=old_entry.food_variant_id and archived_at is null;
            if not found then raise exception 'Unavailable serving measure' using errcode='22023'; end if;
            measured_quantity := (p_input->>'measure_quantity')::numeric;
            if measured_quantity is null or measured_quantity<=0 or measured_quantity>100000
                then raise exception 'Invalid serving count' using errcode='22023'; end if;
            resolved_amount := round(measured_quantity * measure.base_amount,3);
            if resolved_amount<=0 or resolved_amount>1000000
                then raise exception 'Invalid resolved amount' using errcode='22023'; end if;
        end if;
        update public.diary_entries set
            quantity=resolved_amount,
            entered_measure_label=case when p_operation='update_log' and p_input ? 'measure_id' then measure.label
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_label end,
            entered_measure_key=case when p_operation='update_log' and p_input ? 'measure_id' then measure.measure_key
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_key end,
            entered_measure_quantity=case when p_operation='update_log' and p_input ? 'measure_id' then measured_quantity
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_quantity end,
            entered_measure_base_amount=case when p_operation='update_log' and p_input ? 'measure_id' then measure.base_amount
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_base_amount end,
            entered_measure_approximate=case when p_operation='update_log' and p_input ? 'measure_id' then measure.is_approximate
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_approximate end,
            consumed_at=coalesce((p_input->>'consumed_at')::timestamptz,old_entry.consumed_at),
            meal_type=coalesce(p_input->>'meal_type',old_entry.meal_type),
            deleted_at=case when p_operation='delete_log' then now() else null end
            where id=old_entry.id returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,case when p_operation='delete_log' then 'soft_delete' else 'update' end,'diary_entries',entry.id,to_jsonb(old_entry),to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation='repeat_meal' then
        consumed := (p_input->>'consumed_at')::timestamptz;
        for source_id in select value::uuid from jsonb_array_elements_text(p_input->'entry_ids') loop
            select * into strict entry from public.diary_entries where id=source_id and owner_id=uid and deleted_at is null;
            entry.id := gen_random_uuid(); entry.created_at := now(); entry.updated_at := now(); entry.consumed_at := consumed;
            entry.meal_group_id := action_id; entry.meal_type := coalesce(p_input->>'meal_type',entry.meal_type);
            insert into public.diary_entries select (entry).* returning * into entry;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
                values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
            rows_json := rows_json || jsonb_build_array(to_jsonb(entry)); count_rows := count_rows+1;
        end loop;
        if count_rows=0 then raise exception 'Source entries required' using errcode='22023'; end if;
        result := rows_json;
    elsif p_operation='update_targets' then
        delete from private.target_confirmations where id=(p_input->>'confirmation')::uuid and owner_id=uid
            and expires_at>now() and proposed=p_input - 'action_id' - 'actor' - 'confirmation' returning id into token;
        if token is null then raise exception 'Explicit target confirmation required' using errcode='42501'; end if;
        select * into old_targets from public.nutrition_targets where owner_id=uid;
        insert into public.nutrition_targets(owner_id,daily_calories_kcal,daily_protein_g,daily_fat_g,daily_carbs_g,adjustment_limit_ratio)
        values(uid,(p_input->>'daily_calories_kcal')::numeric,(p_input->>'daily_protein_g')::numeric,(p_input->>'daily_fat_g')::numeric,
            (p_input->>'daily_carbs_g')::numeric,(p_input->>'adjustment_limit_ratio')::numeric)
        on conflict(owner_id) do update set daily_calories_kcal=excluded.daily_calories_kcal,daily_protein_g=excluded.daily_protein_g,
            daily_fat_g=excluded.daily_fat_g,daily_carbs_g=excluded.daily_carbs_g,adjustment_limit_ratio=excluded.adjustment_limit_ratio,updated_at=now()
        returning * into targets;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,'target_change','nutrition_targets',uid,case when old_targets.owner_id is null then null else to_jsonb(old_targets) end,to_jsonb(targets));
        result := to_jsonb(targets);
    end if;
    return jsonb_build_object('ok',true,'action_id',action_id,'data',result,'warnings','[]'::jsonb,'undoable',true);
exception when no_data_found then
    raise exception 'Owned item is unavailable' using errcode='22023';
end; $$;
commit;
