-- The two chicken-egg sizes also apply to plain boiled or poached eggs.
-- No recipe (e.g. omelette fried in oil) inherits an egg size without
-- an explicitly sourced per-piece recipe basis.
do $patch$
declare
  def text := pg_get_functiondef('private.seed_variant_measures()'::regprocedure);
  needle text := 'item.name ~* ''^яйцо( куриное)?( сырое)?$''';
begin
  if strpos(def,'варёное (вкрутую|всмятку)')>0 then return; end if;
  if strpos(def,needle)=0 then raise exception 'Seed trigger changed; review egg logic'; end if;
  def:=replace(def,needle,
    'item.name ~* ''^(яйцо( куриное)?( сырое| варёное (вкрутую|всмятку))?|яйцо пашот)$''');
  execute def;
end;
$patch$;

with egg_variants as (
  select v.owner_id,v.id from public.foods f
  join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
  where f.archived_at is null and v.archived_at is null and v.serving_unit='g'
    and f.name in ('Яйцо куриное варёное вкрутую','Яйцо куриное варёное всмятку','Яйцо пашот')
), sizes as (values
  ('egg_medium','Среднее',44::numeric,false),
  ('egg_large','Большое',50::numeric,true)
)
insert into public.food_measures
 (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select e.owner_id,e.id,s.column1,s.column2,s.column3,true,s.column4,
  '≈ съедобная масса одного куриного яйца; способ приготовления может изменить массу'
from egg_variants e cross join sizes s
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- Other eggs/egg parts are a single piece, without M/L size selector.
with items(name, grams) as (values
 ('Яйцо перепелиное',9::numeric),
 ('Яйцо утиное',70::numeric),
 ('Яйцо гусиное',144::numeric),
 ('Яйцо индюшиное',79::numeric),
 ('Яичный белок',30::numeric),
 ('Яичный желток',17::numeric),
 ('Ананас свежий',905::numeric),
 ('Папайя',780::numeric)
)
insert into public.food_measures
(owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select v.owner_id,v.id,'piece','шт.',s.grams,true,true,
  '≈ вес съедобной части одной штуки; размеры продуктов различаются'
from items s join public.foods f on f.name=s.name and f.source like 'TempoLife%'
join public.food_variants v on v.food_id=f.id and v.owner_id=f.owner_id
  and v.archived_at is null and v.serving_unit='g'
on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

-- Copy just-added owner measures into the shared read-only reference.
with measures as (
  select distinct on (ref_v.id, m.measure_key)
    ref_v.id as reference_variant_id,
    substring(v.source_note from 'dataset_id=([0-9]+)') as external_id,
    m.measure_key,m.label,m.base_amount,m.is_approximate,m.is_default,m.source_note
  from public.foods f join public.food_variants v
    on v.food_id=f.id and v.owner_id=f.owner_id
  join public.food_measures m on m.food_variant_id=v.id
    and m.owner_id=v.owner_id and m.archived_at is null
  join public.reference_food_variants ref_v on ref_v.id =
    md5('jetmeal:reference:variant:'||
      substring(v.source_note from 'dataset_id=([0-9]+)'))::uuid
  where f.source like 'TempoLife%'
  order by ref_v.id,m.measure_key,m.created_at
)
insert into public.reference_food_measures
(id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
select md5('jetmeal:reference:measure:'||external_id||':'||measure_key)::uuid,
 reference_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note
from measures on conflict (id) do nothing;
