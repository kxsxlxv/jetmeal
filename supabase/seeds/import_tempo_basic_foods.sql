-- JetMeal: replay the two attributed, Russian-language basic-food seeds.
-- Run from the repository root with psql -v ON_ERROR_STOP=1 -v owner_id=UUID
-- -f supabase/seeds/import_tempo_basic_foods.sql
-- The target must be an existing public.profiles.id. No privileged credentials
-- or user identifiers are stored in this file.
\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE jetmeal_seed_foods (
  source_id text PRIMARY KEY,
  name_ru text NOT NULL CHECK (btrim(name_ru) <> ''),
  name_original text NOT NULL,
  category text NOT NULL,
  calories_kcal numeric NOT NULL CHECK (calories_kcal BETWEEN 0 AND 900),
  protein_g numeric NOT NULL CHECK (protein_g BETWEEN 0 AND 100),
  fat_g numeric NOT NULL CHECK (fat_g BETWEEN 0 AND 100),
  carbs_g numeric NOT NULL CHECK (carbs_g BETWEEN 0 AND 100),
  serving_amount numeric NOT NULL CHECK (serving_amount = 100),
  serving_unit text NOT NULL CHECK (serving_unit = 'g'),
  source text NOT NULL
) ON COMMIT DROP;

\copy jetmeal_seed_foods FROM 'supabase/seeds/tempo_basic_foods_ru.csv' WITH (FORMAT csv, HEADER true)
\copy jetmeal_seed_foods FROM 'supabase/seeds/tempo_usda_staples_ru.csv' WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE jetmeal_seed_owner (id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO jetmeal_seed_owner (id)
SELECT id FROM public.profiles WHERE id = :'owner_id'::uuid;

DO $$
BEGIN
  IF (SELECT count(*) FROM pg_temp.jetmeal_seed_owner) <> 1 THEN
    RAISE EXCEPTION 'Invalid owner_id: no matching JetMeal profile';
  END IF;
  IF (SELECT count(*) FROM pg_temp.jetmeal_seed_foods) <> 426 THEN
    RAISE EXCEPTION 'Unexpected seed row count: expected 426';
  END IF;
END;
$$;

-- Preserve all existing products (including user-authored ones) and diary entries.
-- Stable source IDs and name checks make replays non-destructive and idempotent.
-- An existing active generic product with the same normalized Russian name wins.
WITH new_foods AS (
  INSERT INTO public.foods
    (id, owner_id, name, normalized_name, kind, brand, source)
  SELECT
    md5('jetmeal:tempo:food:' || s.source_id)::uuid,
    o.id,
    s.name_ru,
    lower(btrim(s.name_ru)),
    'generic',
    NULL,
    s.source
  FROM pg_temp.jetmeal_seed_foods AS s
  CROSS JOIN pg_temp.jetmeal_seed_owner AS o
  WHERE NOT EXISTS (
    SELECT 1 FROM public.foods AS prior
    WHERE prior.owner_id = o.id
      AND prior.kind = 'generic'
      AND prior.normalized_name = lower(btrim(s.name_ru))
      AND prior.archived_at IS NULL
  )
  ON CONFLICT (id) DO NOTHING
  RETURNING *
),
new_variants AS (
  INSERT INTO public.food_variants
    (id, owner_id, food_id, serving_amount, serving_unit,
     calories_kcal, protein_g, fat_g, carbs_g, is_estimated, source_note)
  SELECT
    md5('jetmeal:tempo:variant:' || s.source_id)::uuid,
    f.owner_id,
    f.id,
    s.serving_amount,
    s.serving_unit,
    s.calories_kcal,
    s.protein_g,
    s.fat_g,
    s.carbs_g,
    s.source NOT LIKE '%USDA SR Legacy%',
    'Nutrition data from TempoLife (tempolife.app), CC BY 4.0'
      || CASE WHEN s.source LIKE '%USDA SR Legacy%' THEN '; underlying USDA SR Legacy' ELSE '' END
      || '; dataset_id=' || s.source_id
      || '; original=' || s.name_original
      || '; category=' || s.category
  FROM new_foods AS f
  JOIN pg_temp.jetmeal_seed_foods AS s
    ON f.id = md5('jetmeal:tempo:food:' || s.source_id)::uuid
  ON CONFLICT (id) DO NOTHING
  RETURNING *
),
audit_foods AS (
  INSERT INTO public.audit_events
    (owner_id, action_group_id, actor, operation,
     entity_type, entity_id, after_state)
  SELECT
    owner_id,
    md5('jetmeal:tempo:batch:20261011')::uuid,
    'system', 'insert', 'foods', id, to_jsonb(new_foods)
  FROM new_foods
  RETURNING id
),
audit_variants AS (
  INSERT INTO public.audit_events
    (owner_id, action_group_id, actor, operation,
     entity_type, entity_id, after_state)
  SELECT
    owner_id,
    md5('jetmeal:tempo:batch:20261011')::uuid,
    'system', 'insert', 'food_variants', id, to_jsonb(new_variants)
  FROM new_variants
  RETURNING id
)
SELECT
  (SELECT count(*) FROM new_foods) AS foods_inserted,
  (SELECT count(*) FROM new_variants) AS variants_inserted,
  (SELECT count(*) FROM audit_foods) + (SELECT count(*) FROM audit_variants)
    AS audit_events_inserted;

COMMIT;
