"""Prove all migrations against existing Auth + legacy data in an isolated DB.

Uses the running local Supabase PostgreSQL container. Never resets the app DB.
"""
import hashlib
from pathlib import Path
import subprocess
import uuid

container = "supabase_db_jetmeal"
database = "jetmeal_additive_verify_" + uuid.uuid4().hex[:12]


def run(command, data=None):
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode()[-3000:])
    return result.stdout


def sql(text):
    return run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", database,
                "-v", "ON_ERROR_STOP=1", "-A", "-t"], text.encode()).decode()


def fingerprint():
    return hashlib.sha256(sql("select jsonb_build_object(" + ",".join(
        f"'{table}',(select jsonb_agg(to_jsonb(t) order by id) from public.{table} t)"
        for table in ["products", "meal_log", "food_catalog", "meal_entries", "daily_goals", "daily_targets"]
    ) + ", 'legacy_private_acl',(select proacl::text from pg_proc where oid='private.legacy_keep()'::regprocedure),"
        "'legacy_policy',(select pg_get_expr(polqual,polrelid) from pg_policy where polname='legacy_owned'));").encode()).hexdigest()


run(["docker", "exec", container, "createdb", "-U", "postgres", database])
try:
    # Real local Auth types/functions/table definitions, excluding post-data
    # triggers which refer to already-installed JetMeal objects in the app DB.
    auth_schema = run(["docker", "exec", container, "pg_dump", "-U", "postgres", "-d", "postgres",
                       "--schema-only", "--schema=auth", "--section=pre-data", "--no-owner", "--no-privileges"])
    run(["docker", "exec", "-i", container, "psql", "-U", "postgres", "-d", database,
         "-v", "ON_ERROR_STOP=1"], auth_schema)
    sql("""
        alter table auth.users add primary key(id);
        create schema extensions;
        grant usage on schema auth,extensions to authenticated;
        create extension pgcrypto with schema extensions;
        insert into auth.users(id,email) values
          ('33333333-3333-3333-3333-333333333333','existing-a@example.test'),
          ('44444444-4444-4444-4444-444444444444','existing-b@example.test');
        create schema private;
        create function private.legacy_keep() returns integer language sql as $$ select 7 $$;
        revoke all on function private.legacy_keep() from public,anon,authenticated;
        create table public.products(id uuid primary key,user_id uuid,name text,brand text,source text,
            source_type text,nutrition_status text,kcal_per_100g numeric,protein_g_per_100g numeric,
            fat_g_per_100g numeric,carbs_g_per_100g numeric,active boolean,created_at timestamptz,updated_at timestamptz);
        alter table public.products enable row level security;
        create policy legacy_owned on public.products for select to authenticated using(auth.uid()=user_id);
        insert into public.products values
          ('55555555-5555-5555-5555-555555555555','33333333-3333-3333-3333-333333333333',
           'Legacy curd','Fixture','label','packaged','verified',400,30,10,12,true,now(),now()),
          ('66666666-6666-6666-6666-666666666666',null,'Ownerless food',null,'menu','restaurant','verified',80,3,4,5,true,now(),now());
        create table public.meal_log(id uuid primary key,user_id uuid,product_id uuid,meal_type text,
            description text,grams numeric,calories numeric,protein_g numeric,fat_g numeric,carbs_g numeric,
            logged_at timestamptz,source text,created_at timestamptz,updated_at timestamptz);
        insert into public.meal_log values
          ('77777777-7777-7777-7777-777777777777','33333333-3333-3333-3333-333333333333',
           '55555555-5555-5555-5555-555555555555','breakfast','Stored nutrition',125,80,15,3,5,now(),'label',now(),now()),
          ('88888888-8888-8888-8888-888888888888','33333333-3333-3333-3333-333333333333',
           null,'lunch','Incomplete nutrition',250,300,null,null,null,now(),'chatgpt',now(),now());
        create table public.daily_targets(id uuid primary key,user_id uuid,effective_from date,
            calorie_intake_target numeric,protein_target_g numeric,fat_target_g numeric,carbs_target_g numeric,created_at timestamptz);
        insert into public.daily_targets values
          (gen_random_uuid(),'33333333-3333-3333-3333-333333333333',current_date-1,1800,150,50,170,now()-interval '1 day'),
          (gen_random_uuid(),'33333333-3333-3333-3333-333333333333',current_date,2000,190,65,163.8,now()),
          (gen_random_uuid(),'44444444-4444-4444-4444-444444444444',current_date-1,2000,100,70,100,now()-interval '1 day'),
          (gen_random_uuid(),'44444444-4444-4444-4444-444444444444',current_date,2000,null,null,null,now());
        create table public.food_catalog(id bigint primary key,name text);
        create table public.meal_entries(id bigint primary key,name text);
        create table public.daily_goals(id bigint primary key,calories numeric);
        insert into public.food_catalog values(1,'Shared ownerless catalogue');
        insert into public.meal_entries values(1,'Ownerless overlapping source');
        insert into public.daily_goals values(1,2000);
    """)
    original = fingerprint()
    migrations = sorted(Path("supabase/migrations").glob("*.sql"))
    for migration in migrations:
        sql(migration.read_text(encoding="utf-8-sig"))
    assert fingerprint() == original, "Legacy rows, private function ACL or RLS policy changed"
    assert sql("select count(*) from public.profiles;").strip() == "2"
    assert sql("select count(*) from public.foods;").strip() == "1", "Ownerless product imported"
    assert sql("select count(*) from public.diary_entries;").strip() == "1", "Incomplete/duplicate source imported"
    assert sql("select count(*) from public.nutrition_targets;").strip() == "1", "Incomplete latest targets fell back"
    assert sql("select calories_kcal_snapshot from public.diary_entries;").strip() == "80.000", "History rescaled from catalogue"
    assert sql("select daily_protein_g from public.nutrition_targets;").strip() == "190.000"
    assert sql("select count(*) from public.audit_events;").strip() == "4", "Import audit incomplete"
    import_migration = next(path for path in migrations if path.name.endswith("_import_owned_legacy_nutrition.sql"))
    sql(import_migration.read_text(encoding="utf-8-sig"))
    assert sql("select count(*) from public.audit_events;").strip() == "4", "Import replay duplicated history"
    assert fingerprint() == original
    checks = sql(Path("supabase/tests/nutrition_operations.sql").read_text(encoding="utf-8-sig"))
    assert "not ok" not in checks and "1..64" in checks, checks
    print(f"PASS: {len(migrations)} fresh additive migrations; existing Auth backfill; legacy data/private privileges/RLS unchanged; complete imports only; original diary basis; current targets; idempotent import; 64 pgTAP assertions including signed anonymous denial")
finally:
    run(["docker", "exec", container, "dropdb", "-U", "postgres", database])
