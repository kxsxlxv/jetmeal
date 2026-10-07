package com.kxsxlxv.jetmeal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.kxsxlxv.jetmeal.MainActivity
import com.kxsxlxv.jetmeal.ui.number
import com.kxsxlxv.jetmeal.ui.theme.MealDarkColors
import com.kxsxlxv.jetmeal.ui.theme.MealLightColors

class HeroWidget : GlanceAppWidget() {
    // Launchers give slightly different physical sizes to the same 5x2 cell request.
    // Exact makes LocalSize match the actual host allocation so the Hero keeps its geometry.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = HeroWidgetStore(context).read()
        provideContent {
            HeroWidgetContent(context, state)
        }
    }
}

class HeroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HeroWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        HeroWidgetScheduler.ensurePeriodic(context)
        HeroWidgetScheduler.enqueueImmediate(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        HeroWidgetScheduler.ensurePeriodic(context)
        HeroWidgetScheduler.enqueueImmediate(context)
    }

    override fun onDisabled(context: Context) {
        HeroWidgetScheduler.cancel(context)
        super.onDisabled(context)
    }
}

@Composable
private fun HeroWidgetContent(
    context: Context,
    state: HeroWidgetState,
) {
    val size = LocalSize.current
    val openToday = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction("com.kxsxlxv.jetmeal.action.OPEN_TODAY")
            .putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )
    val background = DayNightColorProvider(
        day = MealLightColors.background,
        night = MealDarkColors.background,
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(background)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(openToday)
            .padding(8.dp),
    ) {
        when (state) {
            is HeroWidgetState.Ready -> {
                val widthDp = (size.width.value - 16f).coerceAtLeast(1f)
                val heightDp = (size.height.value - 16f).coerceAtLeast(1f)
                val hero = HeroWidgetRenderer.render(
                    context = context,
                    state = state,
                    widthDp = widthDp,
                    heightDp = heightDp,
                )
                Image(
                    provider = ImageProvider(hero),
                    contentDescription = buildString {
                        append("Калории: ")
                        append(number(state.total.calories))
                        append(" из ")
                        append(number(state.calorieTarget))
                        append(" ккал. Белки: ")
                        append(number(state.total.protein, 1))
                        append(" из ")
                        append(number(state.targets.protein, 1))
                        append(" г. Жиры: ")
                        append(number(state.total.fat, 1))
                        append(" из ")
                        append(number(state.targets.fat, 1))
                        append(" г. Углеводы: ")
                        append(number(state.total.carbs, 1))
                        append(" из ")
                        append(number(state.targets.carbs, 1))
                        append(" г.")
                    },
                    contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            HeroWidgetState.SignedOut -> MessageHero("JetMeal", "Войдите, чтобы видеть прогресс")
            HeroWidgetState.MissingTargets -> MessageHero("Нет целей", "Задайте калории и БЖУ в JetMeal")
            HeroWidgetState.Empty -> MessageHero(
                "JetMeal",
                "Откройте приложение для первой синхронизации",
            )
        }
    }
}

@Composable
private fun MessageHero(title: String, message: String) {
    val onSurface = DayNightColorProvider(
        day = MealLightColors.onSurface,
        night = MealDarkColors.onSurface,
    )
    val onSurfaceVariant = DayNightColorProvider(
        day = MealLightColors.onSurfaceVariant,
        night = MealDarkColors.onSurfaceVariant,
    )
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = TextStyle(
                color = onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            message,
            style = TextStyle(color = onSurfaceVariant, fontSize = 12.sp),
            maxLines = 2,
        )
    }
}
