-- Transactional contract checks for durable offline writes. Requires offline migration.
begin;
insert into auth.users(id,email) values
 ('00000000-0000-0000-0000-00000000e0a1','offline-test-a@jetmeal.test'),
 ('00000000-0000-0000-0000-00000000e0b2','offline-test-b@jetmeal.test');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000e0a1',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000e0a1","role":"authenticated","is_anonymous":false}',true);
do $test$
declare
  input jsonb := '{"snapshot_name":"Offline food","quantity":100,"quantity_unit":"g","calories":150,"protein_g":15,"fat_g":3,"carbs_g":10,"consumed_at":"2026-10-05T09:00:00Z"}'::jsonb;
  first_ack jsonb;
  second_ack jsonb;
  entry_id uuid;
  revision timestamptz;
begin
  first_ack := public.jetmeal_sync_mutation('99999999-9999-4999-8999-999999999999','log_food',input);
  second_ack := public.jetmeal_sync_mutation('99999999-9999-4999-8999-999999999999','log_food',input);
  if first_ack is distinct from second_ack then raise exception 'Receipt should be stable'; end if;
  if (select count(*) from public.diary_entries)<>1 then raise exception 'Request replay produced duplicate entry'; end if;
  if (select count(*) from public.audit_events)<>1 then raise exception 'Request replay produced duplicate audit'; end if;
  begin
    perform public.jetmeal_sync_mutation('99999999-9999-4999-8999-999999999999','log_food',
      input || '{"quantity":200}'::jsonb);
    raise exception 'Client reused request ID with changed payload';
  exception when invalid_parameter_value then null;
  end;
  select id,updated_at into entry_id,revision from public.diary_entries limit 1;
  begin
    perform public.jetmeal_sync_mutation('88888888-8888-4888-8888-888888888888','update_log',
      jsonb_build_object('entry_id',entry_id,'quantity',150),revision-interval '1 second');
    raise exception 'Stale edit bypassed revision check';
  exception when check_violation then null;
  end;
  perform public.jetmeal_sync_mutation('88888888-8888-4888-8888-888888888888','update_log',
      jsonb_build_object('entry_id',entry_id,'quantity',150),revision);
  perform public.jetmeal_sync_mutation('88888888-8888-4888-8888-888888888888','update_log',
      jsonb_build_object('entry_id',entry_id,'quantity',150),revision);
  if (select count(*) from public.audit_events)<>2 then raise exception 'Edit replay duplicated audit'; end if;
  if (select quantity from public.diary_entries limit 1)<>150 then raise exception 'Offline edit failed'; end if;
end $test$;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000e0b2',true);
select set_config('request.jwt.claims','{"sub":"00000000-0000-0000-0000-00000000e0b2","role":"authenticated","is_anonymous":false}',true);
do $test$
begin
  if (select count(*) from public.diary_entries)<>0 then raise exception 'Cross-owner diary leak'; end if;
  if (select count(*) from public.audit_events)<>0 then raise exception 'Cross-owner audit leak'; end if;
end $test$;
reset role;
rollback;
