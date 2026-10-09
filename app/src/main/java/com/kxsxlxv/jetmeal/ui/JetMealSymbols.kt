package com.kxsxlxv.jetmeal.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.kxsxlxv.jetmeal.R

enum class JetMealSymbol(@DrawableRes internal val resource: Int) {
    Settings(R.drawable.symbol_settings),
    Back(R.drawable.symbol_arrow_back),
    Previous(R.drawable.symbol_chevron_left),
    Next(R.drawable.symbol_chevron_right),
    Reset(R.drawable.symbol_history),
    Add(R.drawable.symbol_add),
    ExpandMore(R.drawable.symbol_expand_more),
    ExpandLess(R.drawable.symbol_expand_less),
    Search(R.drawable.symbol_search),
    Edit(R.drawable.symbol_edit),
    Refresh(R.drawable.symbol_refresh),
    Close(R.drawable.symbol_close),
    Logout(R.drawable.symbol_logout),
    Protein(R.drawable.symbol_egg_alt),
    Fat(R.drawable.symbol_water_drop),
    Carbs(R.drawable.symbol_bakery_dining),
    Weight(R.drawable.symbol_weight),
    Serving(R.drawable.symbol_serving),
    Spoon(R.drawable.symbol_spoon),
}

@Composable
fun SymbolIcon(symbol: JetMealSymbol, description: String?, modifier: Modifier = Modifier) {
    Icon(painterResource(symbol.resource), contentDescription = description, modifier = modifier)
}
