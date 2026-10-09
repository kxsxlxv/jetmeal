-- JetMeal offline mutations: exactly-once server execution for at-least-once delivery.
-- Receipts are private and permanently associated with a registered auth user.
create table if not exists private.offline_mutation_receipts (
    owner_id uuid not null references public.profiles(id) on delete cascade,
    request_id uuid not null,
    request_hash text not null,
    response jsonb not null,
    created_at timestamptz not null default now(),
    primary key(owner_id, request_id)
);
revoke all on private.offline_mutation_receipts from public, anon, authenticated;

-- Modification conflicts are checked against a genuine revision timestamp.
-- Existing food operations update this column through the same trigger.
create or replace function private.touch_diary_updated_at()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
revoke all on function private.touch_diary_updated_at() from public, anon, authenticated;
create trigger diary_touch_updated_at before update on public.diary_entries
    for each row execute function private.touch_diary_updated_at();

create or replace function public.jetmeal_sync_mutation(
    p_request_id uuid,
    p_operation text,
    p_input jsonb,
    p_expected_updated_at timestamptz default null
) returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := (select auth.uid());
    stamp text;
    stored private.offline_mutation_receipts;
    response jsonb;
    current_revision timestamptz;
begin
    if uid is null or coalesce((select auth.jwt()->>'is_anonymous')::boolean,false) then
        raise exception 'Registered user required' using errcode='42501';
    end if;
    if p_request_id is null or p_operation not in ('log_food','update_log','delete_log')
        or p_input is null or jsonb_typeof(p_input) <> 'object' then
        raise exception 'Invalid offline request' using errcode='22023';
    end if;
    if p_input ? 'actor' or p_input ? 'action_id' then
        raise exception 'Offline action identity must be generated on server' using errcode='22023';
    end if;
    if (p_operation='log_food' and p_expected_updated_at is not null)
        or (p_operation in ('update_log','delete_log') and p_expected_updated_at is null) then
        raise exception 'Invalid offline edit version' using errcode='22023';
    end if;
    -- Serialize with the ordinary nutrition_operation lock; duplicate retries
    -- see a persisted receipt rather than running an operation twice.
    perform 1 from public.profiles where id=uid for update;
    stamp := md5(p_operation || ':' || p_input::text || ':' ||
        coalesce(p_expected_updated_at::text,''));
    select * into stored from private.offline_mutation_receipts
        where owner_id=uid and request_id=p_request_id for update;
    if found then
        if stored.request_hash <> stamp then
            raise exception 'Offline request ID was reused with different contents' using errcode='22023';
        end if;
        return stored.response;
    end if;
    if p_operation in ('update_log','delete_log') then
        select updated_at into current_revision from public.diary_entries
        where id=(p_input->>'entry_id')::uuid and owner_id=uid and deleted_at is null;
        if current_revision is null or current_revision <> p_expected_updated_at then
            raise exception 'offline_stale_entry' using errcode='23514';
        end if;
    end if;
    response := private.nutrition_operation(p_operation,
        p_input || jsonb_build_object('action_id',p_request_id::text) ||
        case when p_operation='log_food' then
            jsonb_build_object('meal_group_id',p_request_id::text)
        else '{}'::jsonb end);
    insert into private.offline_mutation_receipts(owner_id,request_id,request_hash,response)
    values(uid,p_request_id,stamp,response);
    return response;
end;
$$;
revoke all on function public.jetmeal_sync_mutation(uuid,text,jsonb,timestamptz)
    from public, anon;
grant execute on function public.jetmeal_sync_mutation(uuid,text,jsonb,timestamptz)
    to authenticated;
