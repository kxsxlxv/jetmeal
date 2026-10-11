-- Additive shared nutrition reference; personal foods and history remain owner-scoped.
-- Original catalogue items are retained until each owner explicitly selects a reference.
create table if not exists public.reference_foods (
  id uuid primary key,
  source_ref text not null unique,
  name text not null check (btrim(name) <> ''),
  normalized_name text not null check (btrim(normalized_name) <> ''),
  kind text not null default 'generic',
  brand text,
  source text not null,
  archived_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists public.reference_food_variants (
  id uuid primary key,
  food_id uuid not null references public.reference_foods(id) on delete restrict,
  serving_amount numeric not null check (serving_amount > 0),
  serving_unit text not null check (serving_unit in ('g','ml')),
  calories_kcal numeric not null check (calories_kcal between 0 and 1000000),
  protein_g numeric not null check (protein_g between 0 and 1000000),
  fat_g numeric not null check (fat_g between 0 and 1000000),
  carbs_g numeric not null check (carbs_g between 0 and 1000000),
  is_estimated boolean not null default false,
  source_note text,
  archived_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists public.reference_food_measures (
  id uuid primary key,
  food_variant_id uuid not null references public.reference_food_variants(id) on delete restrict,
  measure_key text not null check (measure_key in ('piece','serving','egg_medium','egg_large','tsp','tbsp','slice','package','cup')),
  label text not null check (length(btrim(label)) between 1 and 32),
  base_amount numeric not null check (base_amount > 0 and base_amount <= 1000000),
  is_approximate boolean not null default true,
  is_default boolean not null default false,
  source_note text,
  archived_at timestamptz,
  created_at timestamptz not null default now()
);
create unique index if not exists reference_food_measures_active_key
  on public.reference_food_measures(food_variant_id,measure_key) where archived_at is null;
create index if not exists reference_foods_name_idx on public.reference_foods(normalized_name) where archived_at is null;
create index if not exists reference_variants_food_idx on public.reference_food_variants(food_id) where archived_at is null;
create index if not exists reference_measures_variant_idx on public.reference_food_measures(food_variant_id) where archived_at is null;

alter table public.reference_foods enable row level security;
alter table public.reference_food_variants enable row level security;
alter table public.reference_food_measures enable row level security;
revoke all on public.reference_foods, public.reference_food_variants, public.reference_food_measures from anon, authenticated;
grant select on public.reference_foods, public.reference_food_variants, public.reference_food_measures to authenticated;
create policy reference_foods_read on public.reference_foods for select to authenticated
  using ((select auth.uid()) is not null and coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)=false);
create policy reference_food_variants_read on public.reference_food_variants for select to authenticated
  using ((select auth.uid()) is not null and coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)=false);
create policy reference_food_measures_read on public.reference_food_measures for select to authenticated
  using ((select auth.uid()) is not null and coalesce((select auth.jwt()->>'is_anonymous')::boolean,false)=false);

-- Seed from the 426 attributed items imported on 2026-10-11, without moving
-- personal diary-linked rows. Shared IDs derive from the *source* ID, not user IDs.
with src as (
  select distinct on (external_id)
    external_id, f.name, f.normalized_name, f.kind, f.brand, f.source
  from public.foods f join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
  cross join lateral (values (substring(v.source_note from 'dataset_id=([0-9]+)'))) ids(external_id)
  where f.source like 'TempoLife%' and ids.external_id is not null
  order by external_id, f.created_at
)
insert into public.reference_foods (id,source_ref,name,normalized_name,kind,brand,source)
select md5('jetmeal:reference:food:'||external_id)::uuid,
       'tempo:'||external_id,name,normalized_name,kind,brand,source
from src
on conflict (id) do nothing;

with src as (
  select distinct on (external_id)
    external_id,v.serving_amount,v.serving_unit,v.calories_kcal,v.protein_g,v.fat_g,v.carbs_g,
    v.is_estimated,v.source_note
  from public.foods f join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
  cross join lateral (values (substring(v.source_note from 'dataset_id=([0-9]+)'))) ids(external_id)
  where f.source like 'TempoLife%' and ids.external_id is not null
  order by external_id, v.created_at
)
insert into public.reference_food_variants
  (id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
select md5('jetmeal:reference:variant:'||external_id)::uuid,
       md5('jetmeal:reference:food:'||external_id)::uuid,
       serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note
from src
on conflict (id) do nothing;

-- Explicit per-item approximate edible weights: never apply one universal
-- '1 piece' conversion across all produce. Source: ordinary USDA standard
-- medium-size reference portions; actual sizes vary.
with piece_weights(name, grams) as (values
  ('Банан',118::numeric),('Яблоко красное',182),('Яблоко зелёное Гренни Смит',182),
  ('Яблоко Фуджи',182),('Яблоко Гала',182),('Апельсин',131),
  ('Апельсин красный',131),('Мандарин',88),('Грейпфрут',230),
  ('Лимон',58),('Лайм',67),('Груша',178),('Персик',150),
  ('Нектарин',142),('Слива',66),('Абрикос',35),('Черешня',5),
  ('Вишня',5),('Айва',92),('Киви',69),('Авокадо',136),
  ('Гранат',174),('Помидор свежий',123),('Помидоры черри',15),
  ('Огурец свежий',200),('Перец болгарский красный',119),
  ('Перец болгарский зелёный',119),('Перец болгарский жёлтый',119),
  ('Лук репчатый сырой',110),('Лук красный',110),('Чеснок',3),
  ('Морковь сырая',61),('Свёкла сырая',82),('Картофель сырой',173),
  ('Баклажан сырой',300),('Капуста белокочанная сырая',900),
  ('Капуста краснокочанная сырая',900),('Клубника',12),
  ('Кумкват',19),('Лук шалот',25),('Лук-порей сырой',89)
)
insert into public.food_measures
    (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select v.owner_id,v.id,'piece','шт.',p.grams,true,true,
    '≈ масса съедобной части типичного плода/овоща; размер и сорт изменяют вес'
from piece_weights p
join public.foods f on f.name=p.name and f.source like 'TempoLife%' and f.archived_at is null
join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id and v.archived_at is null and v.serving_unit='g'
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- Populate read-only reference measures, including egg M/L and sugar spoons
-- already defined by JetMeal's validated variant-specific measure rules.
with src as (
  select distinct on (external_id,m.measure_key)
    external_id,m.measure_key,m.label,m.base_amount,m.is_approximate,m.is_default,m.source_note
  from public.foods f
  join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
  join public.food_measures m on m.food_variant_id=v.id and m.owner_id=v.owner_id and m.archived_at is null
  cross join lateral (values (substring(v.source_note from 'dataset_id=([0-9]+)'))) ids(external_id)
  where f.source like 'TempoLife%' and ids.external_id is not null
  order by external_id,m.measure_key,m.created_at
)
insert into public.reference_food_measures
  (id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:reference:measure:'||external_id||':'||measure_key)::uuid,
  md5('jetmeal:reference:variant:'||external_id)::uuid,
  measure_key,label,base_amount,is_approximate,is_default,source_note
from src on conflict (id) do nothing;
