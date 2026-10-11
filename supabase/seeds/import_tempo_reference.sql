-- Reproducible global catalogue import; no user account or personal rows needed.
-- Must run from the repository root through psql after the reference-table
-- schema migration. Uses only attributed, curated files (not FatSecret data).
\set ON_ERROR_STOP on
begin;
create temp table reference_seed (
  source_id text primary key,
  name_ru text not null,
  name_original text not null,
  category text not null,
  calories_kcal numeric not null check (calories_kcal between 0 and 900),
  protein_g numeric not null check (protein_g between 0 and 100),
  fat_g numeric not null check (fat_g between 0 and 100),
  carbs_g numeric not null check (carbs_g between 0 and 100),
  serving_amount numeric not null check (serving_amount = 100),
  serving_unit text not null check (serving_unit = 'g'),
  source text not null
) on commit drop;
\copy reference_seed from 'supabase/seeds/tempo_basic_foods_ru.csv' with (format csv, header true)
\copy reference_seed from 'supabase/seeds/tempo_usda_staples_ru.csv' with (format csv, header true)
do $assert$
begin
 if (select count(*) from pg_temp.reference_seed)<>426 then
   raise exception 'Shared seed must have exactly 426 rows';
 end if;
end;
$assert$;

insert into public.reference_foods(id,source_ref,name,normalized_name,kind,source)
select md5('jetmeal:reference:food:'||source_id)::uuid,
       'tempo:'||source_id,name_ru,lower(trim(name_ru)),'generic',
       case when source like '%USDA%' then 'TempoLife/USDA SR Legacy (CC BY 4.0)'
            else 'TempoLife (CC BY 4.0)' end
from pg_temp.reference_seed
on conflict(id) do nothing;

insert into public.reference_food_variants
 (id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
select md5('jetmeal:reference:variant:'||source_id)::uuid,
       md5('jetmeal:reference:food:'||source_id)::uuid,
       serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,
       source not like '%USDA%',
       'Nutrition data from TempoLife (tempolife.app), CC BY 4.0; dataset_id='||
         source_id||'; original='||name_original||'; category='||category
from pg_temp.reference_seed
on conflict(id) do nothing;

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
  ('Кумкват',19),('Лук шалот',25),('Лук-порей сырой',89,
  ('Яйцо перепелиное',9),('Яйцо утиное',70),('Яйцо гусиное',144),
  ('Яйцо индюшиное',79),('Яичный белок',30),('Яичный желток',17),
  ('Ананас свежий',905),('Папайя',780)
)
)
insert into public.reference_food_measures
(id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:reference:measure:'||s.source_id||':piece')::uuid,
       md5('jetmeal:reference:variant:'||s.source_id)::uuid,
       'piece','шт.',w.grams,true,true,
       '≈ масса съедобной части одного плода/овоща; фактический размер может отличаться'
from pg_temp.reference_seed s join piece_weights w on w.name=s.name_ru
on conflict(id) do nothing;

with sizes(measure_key,label,grams,is_default) as (
  values ('egg_medium','Среднее',44::numeric,false),
         ('egg_large','Большое',50::numeric,true)
)
insert into public.reference_food_measures
(id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:reference:measure:'||s.source_id||':'||sz.measure_key)::uuid,
       md5('jetmeal:reference:variant:'||s.source_id)::uuid,
       sz.measure_key,sz.label,sz.grams,true,sz.is_default,
       '≈ масса съедобной части одного куриного яйца'
from pg_temp.reference_seed s cross join sizes sz
where s.name_ru in ('Яйцо куриное сырое','Яйцо куриное варёное вкрутую',
                    'Яйцо куриное варёное всмятку','Яйцо пашот')
on conflict(id) do nothing;

with spoons(measure_key,label,grams,is_default) as (
  values ('tsp','ч. л.',4::numeric,false),('tbsp','ст. л.',12::numeric,false)
)
insert into public.reference_food_measures
(id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:reference:measure:'||s.source_id||':'||sp.measure_key)::uuid,
       md5('jetmeal:reference:variant:'||s.source_id)::uuid,
       sp.measure_key,sp.label,sp.grams,true,sp.is_default,
       '≈ ровная ложка белого сахара'
from pg_temp.reference_seed s cross join spoons sp
where s.name_ru='Сахар белый'
on conflict(id) do nothing;

commit;
