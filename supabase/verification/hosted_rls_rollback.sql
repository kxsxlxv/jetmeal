-- DATABASE/RLS verification only: simulates authenticated PostgreSQL roles.
-- This is not proof of hosted password/Email OTP login, session refresh or client login.
-- Everything created or changed in this transaction is rolled back.
begin;
create temporary table jetmeal_verify_state(key text primary key,value jsonb);
grant all on jetmeal_verify_state to authenticated;
insert into jetmeal_verify_state values
    ('owner_a',to_jsonb((select id from auth.users order by created_at,id limit 1))),
    ('owner_b',to_jsonb(gen_random_uuid()));
do $$ begin
    if (select value from jetmeal_verify_state where key='owner_a') is null then
        raise exception 'An existing Auth user is required';
    end if;
end $$;
insert into auth.users(id,email)
    select (value#>>'{}')::uuid,'jetmeal-db-verification-' || (value#>>'{}') || '@example.invalid'
    from jetmeal_verify_state where key='owner_b';
set local role authenticated;
select set_config('request.jwt.claim.sub',(select value#>>'{}' from jetmeal_verify_state where key='owner_a'),true);
do $$ begin
    if (select count(*) from public.profiles)<>1 then raise exception 'Profile RLS failed'; end if;
    if (select count(*) from public.foods where owner_id<>auth.uid())<>0 then raise exception 'Food RLS failed'; end if;
end $$;
insert into jetmeal_verify_state values('food_a',public.jetmeal_create_food(
    '{"name":"Temporary hosted verification food","serving_amount":300,"serving_unit":"g","calories_kcal":435,"protein_g":50.4,"fat_g":15,"carbs_g":9}'));
insert into jetmeal_verify_state values('entry_a',public.jetmeal_log_food(jsonb_build_object(
    'food_variant_id',(select value->'data'->'variant'->>'id' from jetmeal_verify_state where key='food_a'),
    'quantity',125,'consumed_at',now(),'meal_type','snack')));
do $$ declare row_data jsonb; begin
    select value->'data' into row_data from jetmeal_verify_state where key='entry_a';
    if (row_data->>'calories_kcal_snapshot')::numeric<>181.250 then raise exception 'Quantity scaling failed'; end if;
    if row_data->>'meal_type'<>'snack' then raise exception 'Explicit meal override failed'; end if;
end $$;
select public.jetmeal_update_log(jsonb_build_object('entry_id',
    (select value->'data'->>'id' from jetmeal_verify_state where key='entry_a'),'quantity',250));
do $$ begin
    if (select calories_kcal_snapshot from public.diary_entries where id=
        (select (value->'data'->>'id')::uuid from jetmeal_verify_state where key='entry_a'))<>362.500 then
        raise exception 'Quantity correction failed';
    end if;
end $$;
select public.jetmeal_delete_log(jsonb_build_object('entry_id',
    (select value->'data'->>'id' from jetmeal_verify_state where key='entry_a')));
do $$ begin
    if exists(select 1 from public.diary_entries where deleted_at is null and id=
        (select (value->'data'->>'id')::uuid from jetmeal_verify_state where key='entry_a')) then
        raise exception 'Soft deletion failed';
    end if;
end $$;
select public.jetmeal_undo_last_action('{}');
do $$ begin
    if not exists(select 1 from public.diary_entries where deleted_at is null and id=
        (select (value->'data'->>'id')::uuid from jetmeal_verify_state where key='entry_a')) then
        raise exception 'Undo failed';
    end if;
    begin
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type)
            values(auth.uid(),gen_random_uuid(),'user','insert','foods');
        raise exception 'Audit forgery unexpectedly succeeded';
    exception when insufficient_privilege then null; end;
    begin
        perform public.jetmeal_update_targets('{"daily_calories_kcal":2000,"daily_protein_g":170,"daily_fat_g":70,"daily_carbs_g":180,"adjustment_limit_ratio":0.1}');
        raise exception 'Unconfirmed target update unexpectedly succeeded';
    exception when insufficient_privilege then null; end;
end $$;
select set_config('request.jwt.claim.sub',(select value#>>'{}' from jetmeal_verify_state where key='owner_b'),true);
insert into jetmeal_verify_state values('food_b',public.jetmeal_create_food(
    '{"name":"Temporary second owner food","serving_amount":1,"serving_unit":"piece","calories_kcal":100,"protein_g":10,"fat_g":3,"carbs_g":10}'));
select public.jetmeal_log_food(jsonb_build_object('food_variant_id',
    (select value->'data'->'variant'->>'id' from jetmeal_verify_state where key='food_b'),
    'quantity',1,'consumed_at',now(),'meal_type','day'));
insert into jetmeal_verify_state values('target_challenge_b',public.jetmeal_prepare_targets(
    '{"daily_calories_kcal":2000,"daily_protein_g":170,"daily_fat_g":70,"daily_carbs_g":180,"adjustment_limit_ratio":0.1}'));
select public.jetmeal_update_targets('{"daily_calories_kcal":2000,"daily_protein_g":170,"daily_fat_g":70,"daily_carbs_g":180,"adjustment_limit_ratio":0.1}'::jsonb ||
    jsonb_build_object('confirmation',(select value->'data'->>'confirmation' from jetmeal_verify_state where key='target_challenge_b')));
do $$ declare a uuid := (select (value#>>'{}')::uuid from jetmeal_verify_state where key='owner_a'); begin
    if (select count(*) from public.foods)<>1 then raise exception 'Second owner cannot read own food'; end if;
    if exists(select 1 from public.foods where owner_id=a) then raise exception 'Cross-owner foods visible'; end if;
    if exists(select 1 from public.food_variants where owner_id=a) then raise exception 'Cross-owner variants visible'; end if;
    if exists(select 1 from public.diary_entries where owner_id=a) then raise exception 'Cross-owner diary visible'; end if;
    if exists(select 1 from public.nutrition_targets where owner_id=a) then raise exception 'Cross-owner targets visible'; end if;
    if exists(select 1 from public.audit_events where owner_id=a) then raise exception 'Cross-owner audit visible'; end if;
    begin
        perform public.jetmeal_update_log(jsonb_build_object('entry_id',
            (select value->'data'->>'id' from jetmeal_verify_state where key='entry_a'),'quantity',1));
        raise exception 'Cross-owner correction unexpectedly succeeded';
    exception when sqlstate '22023' then null; end;
    begin
        perform public.jetmeal_delete_log(jsonb_build_object('entry_id',
            (select value->'data'->>'id' from jetmeal_verify_state where key='entry_a')));
        raise exception 'Cross-owner deletion unexpectedly succeeded';
    exception when sqlstate '22023' then null; end;
    begin
        perform public.jetmeal_log_food(jsonb_build_object('food_variant_id',
            (select value->'data'->'variant'->>'id' from jetmeal_verify_state where key='food_a'),
            'quantity',1,'consumed_at',now()));
        raise exception 'Cross-owner variant unexpectedly logged';
    exception when sqlstate '22023' then null; end;
    begin
        insert into public.foods(owner_id,name,normalized_name,kind) values(a,'Spoof','spoof','generic');
        raise exception 'Owner spoof unexpectedly succeeded';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
update auth.users set is_anonymous=true where id=(select (value#>>'{}')::uuid from jetmeal_verify_state where key='owner_b');
set local role authenticated;
select set_config('request.jwt.claims',jsonb_build_object('sub',(select value#>>'{}' from jetmeal_verify_state where key='owner_b'),
    'role','authenticated','is_anonymous',true,'user_metadata',jsonb_build_object('is_anonymous',false))::text,true);
do $$ begin
    if exists(select 1 from public.profiles) or exists(select 1 from public.foods)
        or exists(select 1 from public.food_variants) or exists(select 1 from public.diary_entries)
        or exists(select 1 from public.nutrition_targets) or exists(select 1 from public.audit_events) then
        raise exception 'Signed anonymous identity can read its owned data';
    end if;
    begin
        perform public.jetmeal_prepare_targets('{}');
        raise exception 'Signed anonymous identity can prepare a protected mutation';
    exception when insufficient_privilege then null; end;
    begin
        perform public.jetmeal_undo_last_action('{}');
        raise exception 'Signed anonymous identity can call protected mutation';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
set local role anon;
do $$ begin
    begin
        perform 1 from public.diary_entries;
        raise exception 'Anonymous diary unexpectedly accessible';
    exception when insufficient_privilege then null; end;
end $$;
reset role;
rollback;
select 'PASS: transaction-backed owner RLS, protected audit, real RPC scaling/correction/delete/undo, target challenge, unsigned and signed anonymous denial; all test writes rolled back. Hosted login/session not tested here.' as result;
