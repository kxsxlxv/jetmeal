package com.kxsxlxv.jetmeal.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.unit.ColorProvider
import androidx.glance.color.ColorProvider as DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.kxsxlxv.jetmeal.MainActivity
import com.kxsxlxv.jetmeal.ui.number
import com.kxsxlxv.jetmeal.ui.theme.DarkNutritionColors
import com.kxsxlxv.jetmeal.ui.theme.LightNutritionColors

class HeroWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = HeroWidgetStore(context).read()
        val ring = (state as? HeroWidgetState.Ready)?.let { HeroRingRenderer.render(context, it) }
        provideContent {
            GlanceTheme {
                HeroWidgetContent(context, state, ring)
            }
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
    ring: android.graphics.Bitmap?,
) {
    val openToday = actionStartActivity(
        Intent(context, MainActivity::class.java)
            .setAction("com.kxsxlxv.jetmeal.action.OPEN_TODAY")
            .putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(openToday)
            .padding(8.dp),
    ) {
        when (state) {
            is HeroWidgetState.Ready -> if (ring != null) ReadyHero(state, ring) else EmptyHero()
            HeroWidgetState.SignedOut -> MessageHero("JetMeal", "Войдите, чтобы видеть прогресс")
            HeroWidgetState.MissingTargets -> MessageHero("Нет целей", "Задайте калории и БЖУ в JetMeal")
            HeroWidgetState.Empty -> EmptyHero()
        }
    }
}

@Composable
private fun ReadyHero(state: HeroWidgetState.Ready, ring: android.graphics.Bitmap) {
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(ring),
            contentDescription =
                "Калории: ${number(state.total.calories)} из ${number(state.calorieTarget)} ккал",
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.size(94.dp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight().fillMaxHeight()) {
            MacroLine(
                "Белки",
                state.total.protein,
                state.targets.protein,
                MacroPalette.Protein,
                GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            Spacer(GlanceModifier.height(2.dp))
            MacroLine(
                "Жиры",
                state.total.fat,
                state.targets.fat,
                MacroPalette.Fat,
                GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            Spacer(GlanceModifier.height(2.dp))
            MacroLine(
                "Углеводы",
                state.total.carbs,
                state.targets.carbs,
                MacroPalette.Carbs,
                GlanceModifier.fillMaxWidth().defaultWeight(),
            )
        }
    }
}

@Composable
private fun MacroLine(
    label: String,
    actual: Double,
    target: Double,
    palette: MacroPalette,
    modifier: GlanceModifier,
) {
    Column(
        modifier = modifier
            .background(palette.container)
            .cornerRadius(12.dp)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.content, fontSize = 10.sp),
                maxLines = 1,
            )
            Text(
                "${number(actual, 1)} / ${number(target, 1)} г",
                style = TextStyle(
                    color = palette.content,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        LinearProgressIndicator(
            progress = if (target > 0.0) (actual / target).toFloat().coerceIn(0f, 1f) else 0f,
            color = palette.progress,
            backgroundColor = palette.container,
            modifier = GlanceModifier.fillMaxWidth().height(3.dp),
        )
    }
}

@Composable
private fun MessageHero(title: String, message: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            message,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            maxLines = 2,
        )
    }
}

@Composable
private fun EmptyHero() =
    MessageHero("JetMeal", "Откройте приложение для первой синхронизации")

private data class MacroPalette(
    val container: androidx.glance.unit.ColorProvider,
    val content: androidx.glance.unit.ColorProvider,
    val progress: androidx.glance.unit.ColorProvider,
) {
    companion object {
        val Protein = MacroPalette(
            DayNightColorProvider(
                day = LightNutritionColors.protein.container,
                night = DarkNutritionColors.protein.container,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.protein.onContainer,
                night = DarkNutritionColors.protein.onContainer,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.protein.end,
                night = DarkNutritionColors.protein.end,
            ),
        )
        val Fat = MacroPalette(
            DayNightColorProvider(
                day = LightNutritionColors.fat.container,
                night = DarkNutritionColors.fat.container,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.fat.onContainer,
                night = DarkNutritionColors.fat.onContainer,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.fat.end,
                night = DarkNutritionColors.fat.end,
            ),
        )
        val Carbs = MacroPalette(
            DayNightColorProvider(
                day = LightNutritionColors.carbs.container,
                night = DarkNutritionColors.carbs.container,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.carbs.onContainer,
                night = DarkNutritionColors.carbs.onContainer,
            ),
            DayNightColorProvider(
                day = LightNutritionColors.carbs.end,
                night = DarkNutritionColors.carbs.end,
            ),
        )
    }
}
