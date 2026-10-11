-- JetMeal weekly rollover: the previous week's calorie balance remains a
-- historical metric, never a credit/debit carried into a new week's goal.
-- Intra-week redistribution by WeekBudget.calculate stays unchanged.
-- A future adjustment based on measured weight is independent of calorie carry.
alter table public.nutrition_cut_plans
  alter column carryover_cap_week_kcal set default 0;

update public.nutrition_cut_plans
set carryover_cap_week_kcal = 0, updated_at = now()
where status = 'active' and carryover_cap_week_kcal <> 0;

-- Recalculate the ongoing Monday-start week from its 2000 kcal base rather
-- than merely overriding nutrition_targets. The function also updates
-- weekly_nutrition_budgets and versions nutrition_target_history.
select private.recalculate_cut_week(
  id,
  date_trunc('week', now() at time zone 'Europe/Moscow')::date
)
from public.nutrition_cut_plans
where status = 'active';
