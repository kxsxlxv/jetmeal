-- Optional, repeatable JetMeal reference-data enrichment (not a schema migration).
-- Every conversion is variant-specific and keeps the original nutritional basis.
-- Do not apply cups to dried/condensed milk or guess weights for unspecified
-- restaurant items. Run on existing installations after product-measures setup.
begin;

-- A metric glass is 250 ml. Milk ~244 g per 240 ml household cup, or
-- ~254 g per 250 ml. Milk composition/temperature varies; mark approximate.
with milk(name, grams) as (values
  ('Молоко обезжиренное',254.0::numeric),
  ('Молоко полужирное',254.0::numeric),
  ('Молоко цельное',254.0::numeric),
  ('Молоко Простоквашино Отборное 4%',254.0::numeric)
)
insert into public.food_measures
  (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select v.owner_id,v.id,'cup','стакан 250 мл',milk.grams,true,true,
  '≈ 254 г молока в мерном стакане 250 мл; плотность зависит от жирности (USDA: 244 г / 240 мл)'
from milk join public.foods f on f.name=milk.name and f.archived_at is null
join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
where v.archived_at is null and v.serving_unit='g'
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- A 250-ml *volume* is exact for foods whose nutrient basis is in ml.
-- Keep packaged drinks' existing serving volume as their initial preference.
insert into public.food_measures
  (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select v.owner_id,v.id,'cup','стакан 250 мл',250,false,false,
  'Мерный стакан 250 мл; миллилитры уже являются базовой единицей продукта'
from public.foods f join public.food_variants v
  on v.food_id=f.id and v.owner_id=f.owner_id
where f.archived_at is null and v.archived_at is null and v.serving_unit='ml'
  and (f.name like 'Морс из %' or f.name like 'Сок восстановленный %')
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- Different sugar structures have different spoon weights; do not reuse
-- the white-sugar 4g/12g approximation for brown sugar or powdered sugar.
with spoons(name,measure_key,grams) as (values
  ('Сахар коричневый','tsp',4.6::numeric),
  ('Сахар коричневый','tbsp',13.8::numeric),
  ('Сахарная пудра','tsp',2.5::numeric),
  ('Сахарная пудра','tbsp',7.5::numeric)
)
insert into public.food_measures
  (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select v.owner_id,v.id,spoons.measure_key,
  case when spoons.measure_key='tsp' then 'ч. л.' else 'ст. л.' end,
  spoons.grams,true,false,
  '≈ ровная ложка: USDA FoodData Central, коричневый сахар (утрамбованный) / сахарная пудра (непросеянная)'
from spoons join public.foods f on f.name=spoons.name and f.archived_at is null
join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
where v.archived_at is null and v.serving_unit='g'
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- Public reference measures are read-only and have deterministic IDs.
with milk(name, grams) as (values
  ('Молоко обезжиренное',254.0::numeric),
  ('Молоко полужирное',254.0::numeric),
  ('Молоко цельное',254.0::numeric)
)
insert into public.reference_food_measures
  (id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:context-measure:'||v.id::text||':cup')::uuid,
  v.id,'cup','стакан 250 мл',milk.grams,true,true,
  '≈ 254 г молока в 250 мл; USDA FoodData Central: 244 г / 240 мл'
from milk join public.reference_foods f on f.name=milk.name and f.archived_at is null
join public.reference_food_variants v on v.food_id=f.id
where v.archived_at is null and v.serving_unit='g'
on conflict (food_variant_id,measure_key) where archived_at is null do nothing;

with spoons(name,measure_key,grams) as (values
  ('Сахар коричневый','tsp',4.6::numeric),
  ('Сахар коричневый','tbsp',13.8::numeric),
  ('Сахарная пудра','tsp',2.5::numeric),
  ('Сахарная пудра','tbsp',7.5::numeric)
)
insert into public.reference_food_measures
  (id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:context-measure:'||v.id::text||':'||spoons.measure_key)::uuid,
  v.id,spoons.measure_key,
  case when spoons.measure_key='tsp' then 'ч. л.' else 'ст. л.' end,
  spoons.grams,true,false,
  '≈ ровная ложка: USDA FoodData Central; насыпная плотность отличается для разных видов сахара'
from spoons join public.reference_foods f on f.name=spoons.name and f.archived_at is null
join public.reference_food_variants v on v.food_id=f.id
where v.archived_at is null and v.serving_unit='g'
on conflict (food_variant_id,measure_key) where archived_at is null do nothing;

commit;
