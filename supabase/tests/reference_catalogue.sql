begin;
insert into auth.users(id,email) values
  ('00000000-0000-0000-0000-00000000f4a1','reference-test-a@example.test'),
  ('00000000-0000-0000-0000-00000000f4b2','reference-test-b@example.test');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000f4a1',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000f4a1","role":"authenticated","is_anonymous":false}',true);
do $test$
declare
  v uuid;
  m uuid;
  result jsonb;
  twice jsonb;
  request uuid := 'f1111111-2222-4333-8444-555555555555';
  own_variant uuid;
  invalid_rejected boolean := false;
begin
  select rv.id into strict v from public.reference_food_variants rv
    join public.reference_foods f on f.id=rv.food_id where f.name='Банан';
  select id into strict m from public.reference_food_measures where food_variant_id=v and measure_key='piece';
  result := public.jetmeal_sync_mutation(request,'log_food',
    jsonb_build_object('food_variant_id',v,'quantity',236,
      'measure_id',m,'measure_quantity',2,'consumed_at','2026-10-11T12:00:00+03:00'));
  if (result->'data'->>'quantity')::numeric <> 236
     or (result->'data'->>'entered_measure_quantity')::numeric <> 2
     or (result->'data'->>'calories_kcal_snapshot')::numeric <> 210.04 then
    raise exception 'Reference conversion is incorrect';
  end if;
  twice := public.jetmeal_sync_mutation(request,'log_food',
    jsonb_build_object('food_variant_id',v,'quantity',236,
      'measure_id',m,'measure_quantity',2,'consumed_at','2026-10-11T12:00:00+03:00'));
  if twice is distinct from result then
    raise exception 'Offline replay unexpectedly changed response';
  end if;
  own_variant := (result->'data'->>'food_variant_id')::uuid;
  if own_variant=v or not exists (
      select 1 from public.food_variants where id=own_variant and owner_id=auth.uid()
    ) then
    raise exception 'Shared item not materialized into private catalogue';
  end if;
  if (select count(*) from public.diary_entries where owner_id=auth.uid())<>1 then
    raise exception 'Replay created duplicate diary entries';
  end if;
  begin
    perform public.jetmeal_sync_mutation('f1111111-2222-4333-8444-555555555556','log_food',
      jsonb_build_object('food_variant_id',v,'quantity',100,
        'measure_id',(select id from public.reference_food_measures
          where food_variant_id<>v order by id limit 1),
        'measure_quantity',1,'consumed_at','2026-10-11T12:00:00+03:00'));
  exception when invalid_parameter_value then invalid_rejected := true;
  end;
  if not invalid_rejected then raise exception 'Cross-variant measure was accepted'; end if;
end
$test$;
-- Prove the other account has no access to the claimed personal food.
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000f4b2',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000f4b2","role":"authenticated","is_anonymous":false}',true);
do $test$
begin
  if exists(select 1 from public.foods f where f.name='Банан') then
    raise exception 'Owner isolation failed';
  end if;
end
$test$;
reset role;
rollback;