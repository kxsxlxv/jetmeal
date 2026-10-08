begin;
create extension if not exists pgtap with schema extensions;
set search_path=public,extensions;
select no_plan();
insert into auth.users(id,email) values
 ('00000000-0000-0000-0000-000000000001','rls-a@jetmeal.test'),
 ('00000000-0000-0000-0000-000000000002','rls-b@jetmeal.test');
create temp table test_state(key text primary key,value jsonb);
grant all on test_state to authenticated;
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select is((select count(*)::integer from profiles),1,'A sees only own profile');
update profiles set timezone='Europe/Istanbul';
select throws_ok($$update profiles set timezone='Invalid/Zone'$$,'23514','Invalid IANA timezone','Reject invalid timezone');
insert into test_state values('food',jetmeal_create_food('{"name":"Cottage cheese","kind":"packaged","serving_amount":300,"serving_unit":"g","calories_kcal":435,"protein_g":50.4,"fat_g":15,"carbs_g":9,"is_estimated":true}'));
select is((select count(*)::integer from foods),1,'A food created');
select is((select count(*)::integer from food_variants),1,'Variant atomically created');
insert into test_state values('entry',jetmeal_log_food(jsonb_build_object('food_variant_id',(select value->'data'->'variant'->>'id' from test_state where key='food'),'quantity',125,'consumed_at','2026-10-05T05:00:00Z')));
select is((select calories_kcal_snapshot from diary_entries),181.250::numeric,'125g from 300g uses immutable basis');
select is((select meal_type from diary_entries),'morning','Timezone meal inference');
select is((select protein_g_snapshot from diary_entries),21.000::numeric,'Protein scaled from basis');
select is((select is_estimated_snapshot from diary_entries),true,'Estimated variant marker survives absent confidence');
select is((select quantity_unit from diary_entries),'g','Variant natural unit persisted');
insert into test_state values('edit',jetmeal_update_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry'),'quantity',200)));
select is((select calories_kcal_snapshot from diary_entries),290.000::numeric,'Quantity correction recalculates from basis');
select throws_ok($$select jetmeal_update_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry'),'quantity',0))$$,'23514',null,'Reject zero quantity');
select throws_ok($$select jetmeal_update_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry'),'quantity',-1))$$,'23514',null,'Reject negative quantity');
select throws_ok($$insert into audit_events(owner_id,action_group_id,actor,operation,entity_type) values(auth.uid(),gen_random_uuid(),'user','insert','foods')$$,'42501',null,'Client cannot forge audit history');
select throws_ok($$update diary_entries set basis_amount_snapshot=1$$,'42501',null,'Client cannot rewrite basis');
select throws_ok($$update audit_events set actor='ai'$$,'42501',null,'Audit append-only');
select throws_ok($$insert into foods(owner_id,name,normalized_name,kind) values('00000000-0000-0000-0000-000000000002','Spoof','spoof','generic')$$,'42501',null,'Owner spoof write rejected');
select throws_ok($$select jetmeal_update_targets('{"daily_calories_kcal":2000,"daily_protein_g":170,"daily_fat_g":70,"daily_carbs_g":180,"adjustment_limit_ratio":0.1,"confirmation":"00000000-0000-0000-0000-000000000003"}')$$,'42501','Explicit target confirmation required','Unconfirmed targets rejected');
insert into test_state values('proposal','{"daily_calories_kcal":2000,"daily_protein_g":170,"daily_fat_g":70,"daily_carbs_g":180,"adjustment_limit_ratio":0.1}');
insert into test_state values('confirm',jetmeal_prepare_targets((select value from test_state where key='proposal')));
select lives_ok($$select jetmeal_update_targets((select value from test_state where key='proposal') || jsonb_build_object('confirmation',(select value->'data'->>'confirmation' from test_state where key='confirm')))$$,'Confirmed target update');
select is((select daily_calories_kcal from nutrition_targets),2000.000::numeric,'Owned targets persisted');
select throws_ok($$select jetmeal_update_targets((select value from test_state where key='proposal') || jsonb_build_object('confirmation',(select value->'data'->>'confirmation' from test_state where key='confirm')))$$,'42501','Explicit target confirmation required','Confirmation is one-use');
select lives_ok($$select jetmeal_delete_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry')))$$,'Soft delete');
select is((select count(*)::integer from diary_entries where deleted_at is null),0,'Deleted entry excluded from active totals');
select lives_ok($$select jetmeal_undo_last_action('{}')$$,'Undo deletion');
select is((select count(*)::integer from diary_entries where deleted_at is null),1,'Undo restores active entry');
select is((select quantity from diary_entries),200.000::numeric,'Undo preserves corrected quantity');
select lives_ok($$select jetmeal_repeat_meal(jsonb_build_object('entry_ids',jsonb_build_array((select value->'data'->>'id' from test_state where key='entry')),'consumed_at','2026-10-06T15:00:00Z','meal_type','snack'))$$,'Repeat meal atomically');
select is((select count(*)::integer from diary_entries where deleted_at is null),2,'Meal copied');
select is((select meal_type from diary_entries where consumed_at='2026-10-06T15:00:00Z'),'snack','Explicit meal override wins');
select lives_ok($$select jetmeal_undo_last_action('{}')$$,'Undo meal group');
select is((select count(*)::integer from diary_entries where deleted_at is null),1,'Meal-group undo is atomic');
select throws_ok($$select jetmeal_log_food(jsonb_build_object('food_variant_id',(select value->'data'->'variant'->>'id' from test_state where key='food'),'quantity',1,'quantity_unit','ml','consumed_at',now()))$$,'22023','Quantity unit must match variant','Reject implicit unit conversion');
select throws_ok($$select jetmeal_create_food('{"name":"Invalid estimate","serving_amount":1,"serving_unit":"piece","calories_kcal":"NaN","protein_g":0,"fat_g":0,"carbs_g":0}')$$,'23514',null,'Reject nonfinite nutrition');
insert into test_state values('group',jsonb_build_object('id',gen_random_uuid()));
select lives_ok($$select jetmeal_log_food(jsonb_build_object('action_id',(select value->>'id' from test_state where key='group'),'snapshot_name','Estimate one','quantity',1,'quantity_unit','piece','calories',90,'protein_g',1,'fat_g',2,'carbs_g',17,'confidence',0.7,'consumed_at','2026-10-07T10:00:00Z'))$$,'Log estimate with immutable initial basis');
select lives_ok($$select jetmeal_log_food(jsonb_build_object('action_id',(select value->>'id' from test_state where key='group'),'snapshot_name','Estimate two','quantity',2,'quantity_unit','piece','calories',200,'protein_g',5,'fat_g',6,'carbs_g',30,'confidence',0.8,'consumed_at','2026-10-07T10:00:00Z'))$$,'Second log shares action group');
select is((select count(*)::integer from diary_entries where deleted_at is null),3,'Two rows in one action');
select is((select count(*)::integer from diary_entries where food_variant_id is null and is_estimated_snapshot),2,'Ad-hoc estimate snapshots retain estimated marker');
select lives_ok($$select jetmeal_undo_last_action('{}')$$,'Undo multiple rows as one intent');
select is((select count(*)::integer from diary_entries where deleted_at is null),1,'All grouped rows undone');
reset role;
update food_variants set calories_kcal=999,is_estimated=false where id=(select (value->'data'->'variant'->>'id')::uuid from test_state where key='food');
set local role authenticated;
select lives_ok($$select jetmeal_update_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry'),'quantity',125))$$,'Correct after catalogue changed');
select is((select calories_kcal_snapshot from diary_entries where deleted_at is null),181.250::numeric,'Catalogue edits never rewrite historical basis');
select is((select is_estimated_snapshot from diary_entries where deleted_at is null),true,'Catalogue edits never rewrite estimation snapshot');

-- Explicit zero-day confirmation: only past, entry-free days. A later food log clears it.
select lives_ok($select jetmeal_confirm_zero_day('2026-10-04',true)$,'A can confirm a past day with no food');
select is((select count(*)::integer from zero_calorie_days where local_date='2026-10-04'),1,'Confirmed day is visible to its owner');
select throws_ok($select jetmeal_confirm_zero_day('2026-10-05',true)$,'23514','Day already has diary entries','Cannot confirm an existing meal day as empty');
select throws_ok($select jetmeal_confirm_zero_day(current_date,true)$,'22023','Only past days can be confirmed empty','Cannot confirm current day as finished');
savepoint zero_day_write_test;
select lives_ok($select jetmeal_log_food('{"snapshot_name":"Later logged meal","quantity":1,"quantity_unit":"piece","calories":100,"protein_g":10,"fat_g":2,"carbs_g":5,"consumed_at":"2026-10-04T12:00:00Z"}')$,'Log food on a previously confirmed zero day');
select is((select count(*)::integer from zero_calorie_days where local_date='2026-10-04'),0,'Adding food clears stale zero-day marker');
rollback to savepoint zero_day_write_test;
select lives_ok($select jetmeal_confirm_zero_day('2026-10-04',false)$,'Owner can revoke zero-day confirmation');
select is((select count(*)::integer from zero_calorie_days),0,'Revoked confirmation is not treated as zero');
select lives_ok($select jetmeal_confirm_zero_day('2026-10-03',true)$,'A can confirm another empty past day');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select is((select count(*)::integer from foods),0,'B cannot read A foods');
select is((select count(*)::integer from diary_entries),0,'B cannot read A diary');
select is((select count(*)::integer from zero_calorie_days),0,'B cannot read A zero-day markers');
select throws_ok($insert into zero_calorie_days(owner_id,local_date) values(auth.uid(),'2026-10-01')$,'42501',null,'Client cannot insert zero days without validation');
select is((select count(*)::integer from nutrition_targets),0,'B cannot read A targets');
select is((select count(*)::integer from audit_events),0,'B cannot read A audit');
select throws_ok($$select jetmeal_update_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry'),'quantity',10))$$,'22023',null,'B cannot update A entry');
select throws_ok($$select jetmeal_delete_log(jsonb_build_object('entry_id',(select value->'data'->>'id' from test_state where key='entry')))$$,'22023',null,'B cannot delete A entry');
select throws_ok($$select jetmeal_log_food(jsonb_build_object('food_variant_id',(select value->'data'->'variant'->>'id' from test_state where key='food'),'quantity',1,'consumed_at',now()))$$,'22023',null,'B cannot log A variant');
select lives_ok($$select jetmeal_create_food('{"name":"B food","serving_amount":1,"serving_unit":"piece","calories_kcal":100,"protein_g":10,"fat_g":3,"carbs_g":10}')$$,'B can create owned catalogue');
select is((select count(*)::integer from foods),1,'B sees own food');
reset role;
select throws_ok($$update diary_entries set basis_amount_snapshot=1$$,'23514','Nutritional basis is immutable','Even privileged writes preserve diary basis');
select throws_ok($$update diary_entries set is_estimated_snapshot=false where is_estimated_snapshot$$,'23514','Estimate snapshot is immutable','Even privileged writes preserve estimate marker');

-- A real anonymous Auth row with seeded owned test data must still be denied
-- by its signed top-level claim, even if user_metadata claims otherwise.
insert into test_state values('anonymous',jsonb_build_object('id',gen_random_uuid()));
insert into auth.users(id,email,is_anonymous)
    select (value->>'id')::uuid,'anonymous-rls@jetmeal.test',true from test_state where key='anonymous';
insert into public.foods(id,owner_id,name,normalized_name,kind)
    select gen_random_uuid(),(value->>'id')::uuid,'Anonymous fixture','anonymous fixture','generic' from test_state where key='anonymous';
insert into public.food_variants(owner_id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g)
    select owner_id,id,100,'g',100,10,2,3 from public.foods where owner_id=(select (value->>'id')::uuid from test_state where key='anonymous');
insert into public.diary_entries(owner_id,meal_type,snapshot_name,basis_amount_snapshot,basis_unit_snapshot,
    basis_calories_kcal_snapshot,basis_protein_g_snapshot,basis_fat_g_snapshot,basis_carbs_g_snapshot,
    quantity,quantity_unit,calories_kcal_snapshot,protein_g_snapshot,fat_g_snapshot,carbs_g_snapshot,consumed_at)
    select (value->>'id')::uuid,'day','Anonymous fixture',100,'g',100,10,2,3,100,'g',100,10,2,3,now() from test_state where key='anonymous';
insert into public.nutrition_targets(owner_id,daily_calories_kcal)
    select (value->>'id')::uuid,2000 from test_state where key='anonymous';
insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type)
    select (value->>'id')::uuid,gen_random_uuid(),'system','insert','fixture' from test_state where key='anonymous';
set local role authenticated;
select set_config('request.jwt.claim.sub',(select value->>'id' from test_state where key='anonymous'),true);
select set_config('request.jwt.claims',jsonb_build_object('sub',(select value->>'id' from test_state where key='anonymous'),
    'role','authenticated','is_anonymous',true,'user_metadata',jsonb_build_object('is_anonymous',false))::text,true);
select is((select count(*)::integer from profiles),0,'Signed anonymous user cannot read owned profile');
select is((select count(*)::integer from foods),0,'Signed anonymous user cannot read owned foods');
select is((select count(*)::integer from food_variants),0,'Signed anonymous user cannot read owned variants');
select is((select count(*)::integer from diary_entries),0,'Signed anonymous user cannot read owned diary');
select is((select count(*)::integer from nutrition_targets),0,'Signed anonymous user cannot read owned targets');
select is((select count(*)::integer from audit_events),0,'Signed anonymous user cannot read owned audit');
with changed as (update profiles set timezone='Europe/Istanbul' returning id)
    select is(count(*)::integer,0,'Signed anonymous profile update denied') from changed;
select throws_ok($$select jetmeal_create_food('{"name":"Anon rejected","serving_amount":1,"serving_unit":"piece","calories_kcal":100,"protein_g":0,"fat_g":0,"carbs_g":0}')$$,'42501','A registered account is required','Signed anonymous typed write denied');
select throws_ok($$select jetmeal_prepare_targets('{}')$$,'42501','A registered account is required','Signed anonymous confirmation challenge denied');
select throws_ok($$select jetmeal_undo_last_action('{}')$$,'42501','A registered account is required','Signed anonymous undo denied');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-000000000001","role":"authenticated","is_anonymous":false,"user_metadata":{"is_anonymous":true}}',true);
select is((select count(*)::integer from profiles),1,'User metadata cannot masquerade as trusted anonymous claim');
select lives_ok($$select jetmeal_create_food('{"name":"Registered allowed","serving_amount":1,"serving_unit":"piece","calories_kcal":100,"protein_g":0,"fat_g":0,"carbs_g":0}')$$,'Registered account can write with misleading user metadata');
reset role;
select * from finish();
rollback;
