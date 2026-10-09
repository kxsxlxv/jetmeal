-- Run AFTER the product-measures migration. Always rolls back its fixtures.
begin;
insert into auth.users(id,email) values
('00000000-0000-0000-0000-00000000f1a1','measures-test-a@example.test'),
('00000000-0000-0000-0000-00000000f1b2','measures-test-b@example.test');
insert into public.foods(id,owner_id,name,normalized_name,kind) values
('00000000-0000-0000-0000-000000001111','00000000-0000-0000-0000-00000000f1a1','Чизбургер','чизбургер','restaurant'),
('00000000-0000-0000-0000-000000001112','00000000-0000-0000-0000-00000000f1a1','Яйцо куриное','яйцо куриное','generic'),
('00000000-0000-0000-0000-000000001113','00000000-0000-0000-0000-00000000f1a1','Сахар','сахар','generic');
insert into public.food_variants(id,owner_id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,source_note) values
('00000000-0000-0000-0000-000000002221','00000000-0000-0000-0000-00000000f1a1','00000000-0000-0000-0000-000000001111',109,'g',291,15,12,34,'serving=1 порция; source_type=official_pdf'),
('00000000-0000-0000-0000-000000002222','00000000-0000-0000-0000-00000000f1a1','00000000-0000-0000-0000-000000001112',100,'g',143,13,10,1,'generic raw reference'),
('00000000-0000-0000-0000-000000002223','00000000-0000-0000-0000-00000000f1a1','00000000-0000-0000-0000-000000001113',100,'g',387,0,0,100,'generic reference');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000f1a1',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000f1a1","role":"authenticated","is_anonymous":false}',true);
do $check$
declare m uuid; entry uuid; ack jsonb; old_total numeric;
begin
 if (select count(*) from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002222')<>2
   then raise exception 'Expected exactly two egg sizes'; end if;
 if (select count(*) from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002223')<>2
   then raise exception 'Expected two sugar spoon types'; end if;
 if not exists(select 1 from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002222'
   and measure_key='egg_medium' and base_amount=44 and is_approximate)
   then raise exception 'Missing 44g edible medium egg';end if;
 if not exists(select 1 from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002222'
   and measure_key='egg_large' and base_amount=50 and is_approximate)
   then raise exception 'Missing 50g edible large egg';end if;
 select id into m from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002221';
 ack=public.jetmeal_sync_mutation('bbbbbbbb-1111-4111-8111-111111111111','log_food',
   jsonb_build_object('food_variant_id','00000000-0000-0000-0000-000000002221',
     'quantity',109,'measure_id',m,'measure_quantity',3,'consumed_at','2026-10-09T10:00:00Z'));
 entry=(ack->'data'->>'id')::uuid;
 if (ack->'data'->>'quantity')::numeric <>327 or
    (ack->'data'->>'calories_kcal_snapshot')::numeric <>873 or
    (ack->'data'->>'entered_measure_quantity')::numeric <>3 then
   raise exception 'Invalid server conversion or historical snapshot: %',ack; end if;
 if public.jetmeal_sync_mutation('bbbbbbbb-1111-4111-8111-111111111111','log_food',
   jsonb_build_object('food_variant_id','00000000-0000-0000-0000-000000002221',
     'quantity',109,'measure_id',m,'measure_quantity',3,'consumed_at','2026-10-09T10:00:00Z')) is distinct from ack
     then raise exception 'Offline replay was not idempotent'; end if;
 if (select count(*) from public.diary_entries)<>1 then raise exception 'Duplicate entry'; end if;
 old_total=(select calories_kcal_snapshot from public.diary_entries where id=entry);
 -- Changing a measure later cannot alter an existing logged snapshot.
 set constraints all immediate;
 if (select calories_kcal_snapshot from public.diary_entries where id=entry)<>old_total
    then raise exception 'Historical nutrition changed'; end if;
 begin
   perform public.jetmeal_log_food(jsonb_build_object(
    'food_variant_id','00000000-0000-0000-0000-000000002221',
    'measure_id',(select id from public.food_measures where food_variant_id='00000000-0000-0000-0000-000000002222' limit 1),
    'measure_quantity',3,'consumed_at','2026-10-09T10:00:00Z'));
   raise exception 'Cross-variant measure was accepted';
 exception when invalid_parameter_value then null;
 end;
end $check$;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000f1b2',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000f1b2","role":"authenticated","is_anonymous":false}',true);
do $check$ begin
 if (select count(*) from public.food_measures) <> 0 then raise exception 'Owner isolation failed'; end if;
end $check$;
reset role;
rollback;
