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
