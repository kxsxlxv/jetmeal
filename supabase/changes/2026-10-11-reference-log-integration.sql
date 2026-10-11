-- Inject an additive shared-reference resolution branch into the existing
-- authoritative private nutrition operation. Preserve all other operations,
-- offline receipts, target handling, audit logic, and prior migrations.
do $patch$
declare
  current_def text;
  original_lookup text := '            select * into strict variant from public.food_variants where id=(p_input->>''food_variant_id'')::uuid and owner_id=uid and archived_at is null;';
  new_lookup text := $code$
            -- A reference is copied only on its first actual log. The offline
            -- receipt still binds to the original request ID and payload.
            if not exists (
                select 1 from public.food_variants owned
                where owned.id=(p_input->>'food_variant_id')::uuid
                  and owned.owner_id=uid and owned.archived_at is null
            ) then
                reference_claim := private.jetmeal_claim_reference(
                    (p_input->>'food_variant_id')::uuid,
                    case when p_input ? 'measure_id'
                        then (p_input->>'measure_id')::uuid else null end,
                    action_id);
                p_input := jsonb_set(p_input,'{food_variant_id}',
                    to_jsonb(reference_claim->>'food_variant_id'));
                if p_input ? 'measure_id' then
                    p_input := jsonb_set(p_input,'{measure_id}',
                        to_jsonb(reference_claim->>'measure_id'));
                end if;
            end if;
            select * into strict variant from public.food_variants where id=(p_input->>'food_variant_id')::uuid and owner_id=uid and archived_at is null;
$code$;
begin
  current_def := pg_get_functiondef('private.nutrition_operation(text,jsonb)'::regprocedure);
  if strpos(current_def,'reference_claim := private.jetmeal_claim_reference(') > 0 then
    return; -- Idempotent on repository replay.
  end if;
  if strpos(current_def, original_lookup) = 0 or
     strpos(current_def, '    resolved_amount numeric;') = 0 then
    raise exception 'Unexpected nutrition_operation version; inspect before patching';
  end if;
  current_def := replace(current_def, '    resolved_amount numeric;',
                       '    resolved_amount numeric;'||chr(10)||'    reference_claim jsonb;');
  current_def := replace(current_def, original_lookup, new_lookup);
  execute current_def;
end;
$patch$;