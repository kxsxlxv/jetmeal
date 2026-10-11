begin;
insert into auth.users(id,email) values ('00000000-0000-0000-0000-00000000f5a1','reference-egg-test@example.test');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000f5a1',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000f5a1","role":"authenticated","is_anonymous":false}',true);
do $test$
declare
 v uuid;
 med uuid;
 large uuid;
 medium_result jsonb;
 large_result jsonb;
 grams_result jsonb;
begin
 select rv.id into strict v from public.reference_food_variants rv join public.reference_foods f on f.id=rv.food_id where f.name='Яйцо куриное сырое';
 select id into strict med from public.reference_food_measures where food_variant_id=v and measure_key='egg_medium';
 select id into strict large from public.reference_food_measures where food_variant_id=v and measure_key='egg_large';
 medium_result := public.jetmeal_sync_mutation('a1111111-2222-4333-8444-555555555551','log_food',
 jsonb_build_object('food_variant_id',v,'quantity',88,'measure_id',med,'measure_quantity',2,'consumed_at','2026-10-11T12:00:00Z'));
 large_result := public.jetmeal_sync_mutation('a1111111-2222-4333-8444-555555555552','log_food',
 jsonb_build_object('food_variant_id',v,'quantity',150,'measure_id',large,'measure_quantity',3,'consumed_at','2026-10-11T12:00:00Z'));
 grams_result := public.jetmeal_sync_mutation('a1111111-2222-4333-8444-555555555553','log_food',
 jsonb_build_object('food_variant_id',v,'quantity',75,'quantity_unit','g','consumed_at','2026-10-11T12:00:00Z'));
 if (medium_result->'data'->>'quantity')::numeric<>88
    or (medium_result->'data'->>'calories_kcal_snapshot')::numeric<>125.84
    or (medium_result->'data'->>'entered_measure_label')<>'Среднее'
    or (large_result->'data'->>'quantity')::numeric<>150
    or (large_result->'data'->>'calories_kcal_snapshot')::numeric<>214.5
    or (large_result->'data'->>'entered_measure_label')<>'Большое'
    or (grams_result->'data'->>'quantity')::numeric<>75
    or grams_result->'data'->>'entered_measure_label' is not null then
   raise exception 'Egg quantities invalid';
 end if;
 if (select count(*) from public.food_variants where owner_id=auth.uid())<>1 then
   raise exception 'Expected one materialized egg variant, not one per size';
 end if;
end $test$;
reset role;
rollback;