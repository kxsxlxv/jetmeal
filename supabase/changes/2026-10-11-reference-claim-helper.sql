-- A reference is read-only. On first log, atomically materialize only the
-- selected food into the caller's private catalogue, preserving normal diary
-- FKs, immutable basis snapshots, offline replay and personal RLS behavior.
create or replace function private.jetmeal_claim_reference(
  p_reference_variant uuid, p_reference_measure uuid, p_action_id uuid)
returns jsonb
language plpgsql security invoker set search_path = ''
as $body$
declare
  uid uuid := auth.uid();
  rf public.reference_foods;
  rv public.reference_food_variants;
  source_measure public.reference_food_measures;
  own_food public.foods;
  own_variant public.food_variants;
  own_measure uuid;
  target_food_id uuid;
  target_variant_id uuid;
  previous_state jsonb;
  created_or_reactivated boolean;
begin
  if uid is null or coalesce((auth.jwt()->>'is_anonymous')::boolean,false) then
    raise exception 'Registered user required' using errcode='42501';
  end if;
  select * into rv from public.reference_food_variants
    where id=p_reference_variant and archived_at is null;
  if not found then raise exception 'Unavailable reference variant' using errcode='22023'; end if;
  select * into rf from public.reference_foods
    where id=rv.food_id and archived_at is null;
  if not found then raise exception 'Unavailable reference food' using errcode='22023'; end if;
  if p_reference_measure is not null then
    select * into source_measure from public.reference_food_measures
      where id=p_reference_measure and food_variant_id=rv.id and archived_at is null;
    if not found then raise exception 'Reference measure does not belong to variant' using errcode='22023'; end if;
  end if;

  target_food_id := md5('jetmeal:personal:reference:food:'||uid::text||':'||rf.id::text)::uuid;
  target_variant_id := md5('jetmeal:personal:reference:variant:'||uid::text||':'||rv.id::text)::uuid;
  select to_jsonb(f) into previous_state from public.foods f
    where id=target_food_id and owner_id=uid;
  created_or_reactivated := previous_state is null or (previous_state->>'archived_at') is not null;
  insert into public.foods(id,owner_id,name,normalized_name,kind,brand,source)
    values(target_food_id,uid,rf.name,rf.normalized_name,rf.kind,rf.brand,rf.source)
    on conflict(id) do update set archived_at=null,updated_at=now()
      where foods.owner_id=uid and foods.archived_at is not null;
  select * into strict own_food from public.foods
    where id=target_food_id and owner_id=uid and archived_at is null;
  if created_or_reactivated then
    insert into public.audit_events
      (owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
      values(uid,p_action_id,'system',
        case when previous_state is null then 'insert' else 'update' end,
        'foods',own_food.id,previous_state,to_jsonb(own_food));
  end if;

  select to_jsonb(v) into previous_state from public.food_variants v
    where id=target_variant_id and owner_id=uid;
  created_or_reactivated := previous_state is null or (previous_state->>'archived_at') is not null;
  insert into public.food_variants
    (id,owner_id,food_id,serving_amount,serving_unit,
     calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
  values(target_variant_id,uid,target_food_id,rv.serving_amount,rv.serving_unit,
         rv.calories_kcal,rv.protein_g,rv.fat_g,rv.carbs_g,rv.is_estimated,
         'JetMeal shared reference '||rf.source_ref||'; '||coalesce(rv.source_note,''))
  on conflict(id) do update set archived_at=null
    where food_variants.owner_id=uid and food_variants.archived_at is not null;
  select * into strict own_variant from public.food_variants
    where id=target_variant_id and owner_id=uid and food_id=target_food_id and archived_at is null;
  if created_or_reactivated then
    insert into public.audit_events
      (owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
      values(uid,p_action_id,'system',
        case when previous_state is null then 'insert' else 'update' end,
        'food_variants',own_variant.id,previous_state,to_jsonb(own_variant));
  end if;

  -- Variant insert triggers may already create default egg/sugar measures.
  -- Merge by measure key rather than assuming the trigger's random UUID.
  insert into public.food_measures
    (owner_id,food_variant_id,measure_key,label,base_amount,is_approximate,is_default,source_note)
  select uid,target_variant_id,cm.measure_key,cm.label,cm.base_amount,
         cm.is_approximate,cm.is_default,cm.source_note
  from public.reference_food_measures cm
  where cm.food_variant_id=p_reference_variant and cm.archived_at is null
  on conflict (owner_id,food_variant_id,measure_key) where archived_at is null do nothing;

  if p_reference_measure is not null then
    select m.id into own_measure from public.food_measures m
    where m.owner_id=uid and m.food_variant_id=target_variant_id
      and m.measure_key=source_measure.measure_key and m.archived_at is null;
    if own_measure is null then
      raise exception 'Failed to resolve reference measure' using errcode='22023';
    end if;
  end if;
  return jsonb_build_object('food_variant_id',target_variant_id,'measure_id',own_measure);
end;
$body$;
revoke all on function private.jetmeal_claim_reference(uuid,uuid,uuid) from public,anon,authenticated;
