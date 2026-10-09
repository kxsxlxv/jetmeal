begin;
-- Optional food-specific measures. Base unit in food_variants remains authoritative.
create table if not exists public.food_measures (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null,
  food_variant_id uuid not null,
  measure_key text not null check (measure_key in ('piece','serving','egg_medium','egg_large','tsp','tbsp','slice','package','cup')),
  label text not null check (char_length(trim(label)) between 1 and 32),
  base_amount numeric(10,3) not null check (base_amount>0 and base_amount<=1000000),
  is_approximate boolean not null default false,
  is_default boolean not null default false,
  source_note text,
  created_at timestamptz not null default now(),
  archived_at timestamptz,
  constraint food_measures_variant_owner_fk foreign key (food_variant_id,owner_id)
     references public.food_variants(id,owner_id) on delete restrict,
  unique (id,owner_id)
);
create unique index if not exists food_measures_variant_active_key
 on public.food_measures(owner_id,food_variant_id,measure_key) where archived_at is null;
create index if not exists food_measures_variant_idx
 on public.food_measures(owner_id,food_variant_id) where archived_at is null;
alter table public.food_measures enable row level security;
revoke all on public.food_measures from public, anon, authenticated;
grant select on public.food_measures to authenticated;
create policy food_measures_select_own on public.food_measures for select to authenticated
 using ((select auth.uid())=owner_id);

-- Original entered measure is an immutable-time snapshot even if its definition is archived.
alter table public.diary_entries
 add column if not exists entered_measure_label text,
 add column if not exists entered_measure_key text,
 add column if not exists entered_measure_quantity numeric(10,3),
 add column if not exists entered_measure_base_amount numeric(10,3),
 add column if not exists entered_measure_approximate boolean;
alter table public.diary_entries
 add constraint diary_entered_measure_consistent check (
   (entered_measure_label is null and entered_measure_key is null
    and entered_measure_quantity is null and entered_measure_base_amount is null
    and entered_measure_approximate is null)
   or
   (entered_measure_label is not null and entered_measure_key is not null
    and entered_measure_quantity>0 and entered_measure_base_amount>0
    and entered_measure_approximate is not null)
 );

-- Conservative automatic measures. Never pretend a 100 g nutrition reference is a whole burger.
create or replace function private.seed_variant_measures() returns trigger
 language plpgsql security definer set search_path='' as $seed$
declare
  item public.foods;
begin
  select * into item from public.foods where id=new.food_id and owner_id=new.owner_id;
  if new.serving_unit='g' and item.kind='restaurant'
    and new.source_note like '%serving=1 порция%' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,source_note)
    values(new.owner_id,new.id,
      case when item.name ~* '(бургер|маффин)' then 'piece' else 'serving' end,
      case when item.name ~* '(бургер|маффин)' then 'шт.' else 'порция' end,
      new.serving_amount,true,'Официальная порция из источника продукта')
    on conflict do nothing;
  end if;
  if new.serving_unit='g' and item.name ~* '^яйцо( куриное)?( сырое)?$' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,is_approximate,source_note) values
      (new.owner_id,new.id,'egg_medium','Среднее',44,false,true,
       'Приблизительно 44 г съедобной части'),
      (new.owner_id,new.id,'egg_large','Большое',50,true,true,
       'Приблизительно 50 г съедобной части')
    on conflict do nothing;
  end if;
  if new.serving_unit='g' and item.name ~* '^сахар( белый| песок)?$' then
    insert into public.food_measures(owner_id,food_variant_id,measure_key,label,
      base_amount,is_default,is_approximate,source_note) values
      (new.owner_id,new.id,'tsp','ч. л.',4,false,true,'Ровная чайная ложка, приблизительно'),
      (new.owner_id,new.id,'tbsp','ст. л.',12,false,true,'Ровная столовая ложка, приблизительно')
    on conflict do nothing;
  end if;
  return new;
end $seed$;
revoke all on function private.seed_variant_measures() from public,anon,authenticated;
create trigger food_variant_seed_measures after insert on public.food_variants
 for each row execute function private.seed_variant_measures();

-- Existing official portions are backfilled with the same conservative rule.
insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,source_note)
select v.owner_id,v.id,
 case when f.name ~* '(бургер|маффин)' then 'piece' else 'serving' end,
 case when f.name ~* '(бургер|маффин)' then 'шт.' else 'порция' end,
 v.serving_amount,true,'Официальная порция из источника продукта'
from public.food_variants v join public.foods f
 on f.id=v.food_id and f.owner_id=v.owner_id
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.kind='restaurant' and v.source_note like '%serving=1 порция%'
on conflict do nothing;

insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,is_approximate,source_note)
select v.owner_id,v.id,m.key,m.label,m.grams,m.def,true,m.source
from public.food_variants v join public.foods f on f.id=v.food_id and f.owner_id=v.owner_id
cross join (values ('egg_medium','Среднее',44.0,false,'Примерно 44 г съедобной части'),
                   ('egg_large','Большое',50.0,true,'Примерно 50 г съедобной части'))
 as m(key,label,grams,def,source)
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.name ~* '^яйцо( куриное)?( сырое)?

on conflict do nothing;
insert into public.food_measures(owner_id,food_variant_id,measure_key,label,base_amount,
 is_default,is_approximate,source_note)
select v.owner_id,v.id,m.key,m.label,m.grams,false,true,m.source
from public.food_variants v join public.foods f on f.id=v.food_id and f.owner_id=v.owner_id
cross join (values ('tsp','ч. л.',4.0,'Примерно 4 г сахара'),
                   ('tbsp','ст. л.',12.0,'Примерно 12 г сахара'))
 as m(key,label,grams,source)
where v.archived_at is null and f.archived_at is null and v.serving_unit='g'
 and f.name ~* '^сахар( белый| песок)?

on conflict do nothing;

create or replace function private.nutrition_operation(p_operation text,p_input jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    uid uuid := auth.uid();
    action_id uuid := coalesce((p_input->>'action_id')::uuid,gen_random_uuid());
    actor text := coalesce(p_input->>'actor','user');
    food public.foods;
    variant public.food_variants;
    entry public.diary_entries;
    old_entry public.diary_entries;
    targets public.nutrition_targets;
    old_targets public.nutrition_targets;
    event public.audit_events;
    before_json jsonb;
    after_json jsonb;
    result jsonb;
    rows_json jsonb := '[]'::jsonb;
    source_id uuid;
    token uuid;
    zone text;
    consumed timestamptz;
    meal text;
    undo_id uuid;
    count_rows integer := 0;
    measure public.food_measures;
    measured_quantity numeric;
    resolved_amount numeric;
begin
    if uid is null then raise exception 'Authentication required' using errcode='42501'; end if;
    if actor not in ('user','ai') then raise exception 'Invalid actor' using errcode='22023'; end if;
    -- Serialize each user's writes/undo so one undo cannot race a new action.
    perform 1 from public.profiles where id=uid for update;
    select timezone into strict zone from public.profiles where id=uid;
    if p_operation='prepare_targets' then
        insert into private.target_confirmations(owner_id,proposed)
        values(uid,p_input - 'action_id' - 'actor' - 'confirmation') returning id into token;
        return jsonb_build_object('ok',true,'action_id',null,'data',jsonb_build_object('confirmation',token),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation='undo_last_action' then
        select id into undo_id from private.action_groups where owner_id=uid and undone_at is null
            order by created_at desc,id desc limit 1 for update;
        if undo_id is null then raise exception 'No undoable action' using errcode='22023'; end if;
        for event in select * from public.audit_events where owner_id=uid and action_group_id=undo_id
            order by event_sequence desc loop
            if event.entity_type='diary_entries' then
                select * into strict entry from public.diary_entries where id=event.entity_id and owner_id=uid;
                before_json := to_jsonb(entry);
                if event.before_state is null then
                    update public.diary_entries set deleted_at=now() where id=entry.id returning * into entry;
                else
                    old_entry := jsonb_populate_record(null::public.diary_entries,event.before_state);
                    update public.diary_entries set quantity=old_entry.quantity,consumed_at=old_entry.consumed_at,
                        meal_type=old_entry.meal_type,deleted_at=old_entry.deleted_at,
                        entered_measure_label=old_entry.entered_measure_label,
                        entered_measure_key=old_entry.entered_measure_key,
                        entered_measure_quantity=old_entry.entered_measure_quantity,
                        entered_measure_base_amount=old_entry.entered_measure_base_amount,
                        entered_measure_approximate=old_entry.entered_measure_approximate
                        where id=entry.id returning * into entry;
                end if;
                after_json := to_jsonb(entry);
            elsif event.entity_type='food_variants' then
                select to_jsonb(v) into before_json from public.food_variants v where id=event.entity_id and owner_id=uid;
                update public.food_variants set archived_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(food_variants.*) into after_json;
            elsif event.entity_type='foods' then
                select to_jsonb(f) into before_json from public.foods f where id=event.entity_id and owner_id=uid;
                update public.foods set archived_at=now(),updated_at=now() where id=event.entity_id and owner_id=uid returning to_jsonb(foods.*) into after_json;
            elsif event.entity_type='nutrition_targets' then
                select to_jsonb(t) into before_json from public.nutrition_targets t where owner_id=uid;
                if event.before_state is null then
                    delete from public.nutrition_targets where owner_id=uid;
                    after_json := null;
                else
                    old_targets := jsonb_populate_record(null::public.nutrition_targets,event.before_state);
                    update public.nutrition_targets set daily_calories_kcal=old_targets.daily_calories_kcal,
                        daily_protein_g=old_targets.daily_protein_g,daily_fat_g=old_targets.daily_fat_g,
                        daily_carbs_g=old_targets.daily_carbs_g,adjustment_limit_ratio=old_targets.adjustment_limit_ratio,
                        updated_at=now() where owner_id=uid returning to_jsonb(nutrition_targets.*) into after_json;
                end if;
            else raise exception 'Unsupported audit entity'; end if;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
                values(uid,action_id,actor,'undo',event.entity_type,event.entity_id,before_json,after_json);
            count_rows := count_rows+1;
        end loop;
        update private.action_groups set undone_at=now() where id=undo_id;
        return jsonb_build_object('ok',true,'action_id',action_id,'data',jsonb_build_object('undone_action_id',undo_id,'count',count_rows),
            'warnings','[]'::jsonb,'undoable',false);
    end if;
    if p_operation not in ('create_food','log_food','update_log','delete_log','repeat_meal','update_targets') then
        raise exception 'Unknown operation' using errcode='22023';
    end if;
    insert into private.action_groups(id,owner_id) values(action_id,uid) on conflict(id) do nothing;
    if not exists(select 1 from private.action_groups where id=action_id and owner_id=uid and undone_at is null) then
        raise exception 'Invalid action group' using errcode='42501';
    end if;
    if p_operation='create_food' then
        insert into public.foods(owner_id,name,normalized_name,kind,brand,source)
        values(uid,p_input->>'name',lower(trim(p_input->>'name')),coalesce(p_input->>'kind','generic'),p_input->>'brand',p_input->>'source')
        returning * into food;
        insert into public.food_variants(owner_id,food_id,serving_amount,serving_unit,calories_kcal,protein_g,fat_g,carbs_g,is_estimated,source_note)
        values(uid,food.id,(p_input->>'serving_amount')::numeric,p_input->>'serving_unit',
            (p_input->>'calories_kcal')::numeric,(p_input->>'protein_g')::numeric,(p_input->>'fat_g')::numeric,
            (p_input->>'carbs_g')::numeric,coalesce((p_input->>'is_estimated')::boolean,false),p_input->>'source_note') returning * into variant;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state) values
            (uid,action_id,actor,'insert','foods',food.id,to_jsonb(food)),
            (uid,action_id,actor,'insert','food_variants',variant.id,to_jsonb(variant));
        result := jsonb_build_object('food',to_jsonb(food),'variant',to_jsonb(variant));
    elsif p_operation='log_food' then
        if p_input ? 'measure_quantity' and not p_input ? 'measure_id' then
            raise exception 'Measure identifier required' using errcode='22023';
        end if;
        consumed := (p_input->>'consumed_at')::timestamptz;
        meal := coalesce(p_input->>'meal_type',case when extract(hour from consumed at time zone zone) between 5 and 11 then 'morning'
            when extract(hour from consumed at time zone zone) between 12 and 16 then 'day' else 'evening' end);
        if p_input->>'food_variant_id' is not null then
            select * into strict variant from public.food_variants where id=(p_input->>'food_variant_id')::uuid and owner_id=uid and archived_at is null;
            select * into strict food from public.foods where id=variant.food_id and owner_id=uid and archived_at is null;
            if p_input->>'quantity_unit' is not null and p_input->>'quantity_unit' <> variant.serving_unit then
                raise exception 'Quantity unit must match variant' using errcode='22023';
            end if;
            entry.food_id := food.id; entry.food_variant_id := variant.id; entry.snapshot_name := food.name; entry.snapshot_brand := food.brand;
            entry.basis_amount_snapshot := variant.serving_amount; entry.basis_unit_snapshot := variant.serving_unit;
            entry.basis_calories_kcal_snapshot := variant.calories_kcal; entry.basis_protein_g_snapshot := variant.protein_g;
            entry.basis_fat_g_snapshot := variant.fat_g; entry.basis_carbs_g_snapshot := variant.carbs_g;
        else
            entry.snapshot_name := p_input->>'snapshot_name'; entry.snapshot_brand := p_input->>'snapshot_brand';
            entry.basis_amount_snapshot := (p_input->>'quantity')::numeric; entry.basis_unit_snapshot := p_input->>'quantity_unit';
            entry.basis_calories_kcal_snapshot := (p_input->>'calories')::numeric; entry.basis_protein_g_snapshot := (p_input->>'protein_g')::numeric;
            entry.basis_fat_g_snapshot := (p_input->>'fat_g')::numeric; entry.basis_carbs_g_snapshot := (p_input->>'carbs_g')::numeric;
        end if;
        resolved_amount := (p_input->>'quantity')::numeric;
        if p_input ? 'measure_id' then
            if entry.food_variant_id is null then
                raise exception 'Measures require a catalogue variant' using errcode='22023';
            end if;
            select * into measure from public.food_measures
                where id=(p_input->>'measure_id')::uuid and owner_id=uid
                  and food_variant_id=entry.food_variant_id and archived_at is null;
            if not found then raise exception 'Unavailable serving measure' using errcode='22023'; end if;
            measured_quantity := (p_input->>'measure_quantity')::numeric;
            if measured_quantity is null or measured_quantity<=0 or measured_quantity>100000
                then raise exception 'Invalid serving count' using errcode='22023'; end if;
            resolved_amount := round(measured_quantity * measure.base_amount,3);
            if resolved_amount<=0 or resolved_amount>1000000
                then raise exception 'Invalid resolved amount' using errcode='22023'; end if;
            entry.entered_measure_label := measure.label;
            entry.entered_measure_key := measure.measure_key;
            entry.entered_measure_quantity := measured_quantity;
            entry.entered_measure_base_amount := measure.base_amount;
            entry.entered_measure_approximate := measure.is_approximate;
        end if;
        insert into public.diary_entries(owner_id,food_id,food_variant_id,meal_group_id,meal_type,snapshot_name,snapshot_brand,
            basis_amount_snapshot,basis_unit_snapshot,basis_calories_kcal_snapshot,basis_protein_g_snapshot,basis_fat_g_snapshot,basis_carbs_g_snapshot,
            quantity,quantity_unit,calories_kcal_snapshot,protein_g_snapshot,fat_g_snapshot,carbs_g_snapshot,confidence,consumed_at,
            entered_measure_label,entered_measure_key,entered_measure_quantity,
            entered_measure_base_amount,entered_measure_approximate)
        values(uid,entry.food_id,entry.food_variant_id,(p_input->>'meal_group_id')::uuid,meal,entry.snapshot_name,entry.snapshot_brand,
            entry.basis_amount_snapshot,entry.basis_unit_snapshot,entry.basis_calories_kcal_snapshot,entry.basis_protein_g_snapshot,entry.basis_fat_g_snapshot,entry.basis_carbs_g_snapshot,
            resolved_amount,entry.basis_unit_snapshot,0,0,0,0,(p_input->>'confidence')::numeric,consumed,
            entry.entered_measure_label,entry.entered_measure_key,entry.entered_measure_quantity,
            entry.entered_measure_base_amount,entry.entered_measure_approximate) returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
            values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation in ('update_log','delete_log') then
        select * into strict old_entry from public.diary_entries where id=(p_input->>'entry_id')::uuid and owner_id=uid and deleted_at is null for update;
        if p_operation='update_log' and p_input ? 'measure_quantity' and not p_input ? 'measure_id' then
            raise exception 'Measure identifier required' using errcode='22023';
        end if;
        resolved_amount := coalesce((p_input->>'quantity')::numeric,old_entry.quantity);
        if p_operation='update_log' and p_input ? 'measure_id' then
            select * into measure from public.food_measures
              where id=(p_input->>'measure_id')::uuid and owner_id=uid
                and food_variant_id=old_entry.food_variant_id and archived_at is null;
            if not found then raise exception 'Unavailable serving measure' using errcode='22023'; end if;
            measured_quantity := (p_input->>'measure_quantity')::numeric;
            if measured_quantity is null or measured_quantity<=0 or measured_quantity>100000
                then raise exception 'Invalid serving count' using errcode='22023'; end if;
            resolved_amount := round(measured_quantity * measure.base_amount,3);
            if resolved_amount<=0 or resolved_amount>1000000
                then raise exception 'Invalid resolved amount' using errcode='22023'; end if;
        end if;
        update public.diary_entries set
            quantity=resolved_amount,
            entered_measure_label=case when p_operation='update_log' and p_input ? 'measure_id' then measure.label
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_label end,
            entered_measure_key=case when p_operation='update_log' and p_input ? 'measure_id' then measure.measure_key
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_key end,
            entered_measure_quantity=case when p_operation='update_log' and p_input ? 'measure_id' then measured_quantity
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_quantity end,
            entered_measure_base_amount=case when p_operation='update_log' and p_input ? 'measure_id' then measure.base_amount
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_base_amount end,
            entered_measure_approximate=case when p_operation='update_log' and p_input ? 'measure_id' then measure.is_approximate
                when p_operation='update_log' and p_input ? 'quantity' then null else old_entry.entered_measure_approximate end,
            consumed_at=coalesce((p_input->>'consumed_at')::timestamptz,old_entry.consumed_at),
            meal_type=coalesce(p_input->>'meal_type',old_entry.meal_type),
            deleted_at=case when p_operation='delete_log' then now() else null end
            where id=old_entry.id returning * into entry;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,case when p_operation='delete_log' then 'soft_delete' else 'update' end,'diary_entries',entry.id,to_jsonb(old_entry),to_jsonb(entry));
        result := to_jsonb(entry);
    elsif p_operation='repeat_meal' then
        consumed := (p_input->>'consumed_at')::timestamptz;
        for source_id in select value::uuid from jsonb_array_elements_text(p_input->'entry_ids') loop
            select * into strict entry from public.diary_entries where id=source_id and owner_id=uid and deleted_at is null;
            entry.id := gen_random_uuid(); entry.created_at := now(); entry.updated_at := now(); entry.consumed_at := consumed;
            entry.meal_group_id := action_id; entry.meal_type := coalesce(p_input->>'meal_type',entry.meal_type);
            insert into public.diary_entries select (entry).* returning * into entry;
            insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,after_state)
                values(uid,action_id,actor,'insert','diary_entries',entry.id,to_jsonb(entry));
            rows_json := rows_json || jsonb_build_array(to_jsonb(entry)); count_rows := count_rows+1;
        end loop;
        if count_rows=0 then raise exception 'Source entries required' using errcode='22023'; end if;
        result := rows_json;
    elsif p_operation='update_targets' then
        delete from private.target_confirmations where id=(p_input->>'confirmation')::uuid and owner_id=uid
            and expires_at>now() and proposed=p_input - 'action_id' - 'actor' - 'confirmation' returning id into token;
        if token is null then raise exception 'Explicit target confirmation required' using errcode='42501'; end if;
        select * into old_targets from public.nutrition_targets where owner_id=uid;
        insert into public.nutrition_targets(owner_id,daily_calories_kcal,daily_protein_g,daily_fat_g,daily_carbs_g,adjustment_limit_ratio)
        values(uid,(p_input->>'daily_calories_kcal')::numeric,(p_input->>'daily_protein_g')::numeric,(p_input->>'daily_fat_g')::numeric,
            (p_input->>'daily_carbs_g')::numeric,(p_input->>'adjustment_limit_ratio')::numeric)
        on conflict(owner_id) do update set daily_calories_kcal=excluded.daily_calories_kcal,daily_protein_g=excluded.daily_protein_g,
            daily_fat_g=excluded.daily_fat_g,daily_carbs_g=excluded.daily_carbs_g,adjustment_limit_ratio=excluded.adjustment_limit_ratio,updated_at=now()
        returning * into targets;
        insert into public.audit_events(owner_id,action_group_id,actor,operation,entity_type,entity_id,before_state,after_state)
            values(uid,action_id,actor,'target_change','nutrition_targets',uid,case when old_targets.owner_id is null then null else to_jsonb(old_targets) end,to_jsonb(targets));
        result := to_jsonb(targets);
    end if;
    return jsonb_build_object('ok',true,'action_id',action_id,'data',result,'warnings','[]'::jsonb,'undoable',true);
exception when no_data_found then
    raise exception 'Owned item is unavailable' using errcode='22023';
end; $$;
commit;
