package com.kxsxlxv.jetmeal.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import com.kxsxlxv.jetmeal.domain.ChosenMeasure
import com.kxsxlxv.jetmeal.domain.FoodMeasure
import com.kxsxlxv.jetmeal.domain.Nutrition
import com.kxsxlxv.jetmeal.ui.theme.JetmealTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Pure Compose coverage; database security and idempotence are in SQL tests. */
class FoodMeasureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun threeBurgersShowGramEquivalentAndCalorieTotal() {
        val piece=FoodMeasure("piece-id","burger","piece","шт.",109.0,isDefault=true)
        var saved: ChosenMeasure?=null
        compose.setContent {
            JetmealTheme {
                Surface {
                    AmountContent(name="Чизбургер",unit="g",amount=109.0,
                        basisAmount=109.0,nutrition=Nutrition(291.0,15.0,12.0,34.0),
                        estimated=false,busy=false,error=null,onSave={},
                        onDelete=null,onSuccess={},onBack=null,
                        measures=listOf(piece),measurePreferenceKey="burgers-ui-test",
                        onMeasuredSave={ _,selection -> saved=selection })
                }
            }
        }
        compose.onNodeWithText("шт.").assertExists()
        compose.onNodeWithText("109 г",substring=true).assertExists()
        compose.onNodeWithText("291 ккал").assertExists()
        compose.onNodeWithText("Добавить еду").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(109.0,saved!!.baseAmount,.001)
            assertEquals("piece",saved!!.measure.key)
        }
    }

    @Test fun bananaBeginsAtOnePieceAndTwoPiecesUse236Grams() {
        val piece=FoodMeasure("banana-piece","banana","piece","шт.",118.0,
            approximate=true,isDefault=true)
        var saved: ChosenMeasure?=null
        compose.setContent {
            JetmealTheme { Surface {
                AmountContent(name="Банан",unit="g",amount=100.0,basisAmount=100.0,
                    nutrition=Nutrition(89.0,1.1,0.3,23.0),
                    estimated=true,busy=false,error=null,onSave={},onDelete=null,
                    onSuccess={},onBack=null,measures=listOf(piece),
                    measurePreferenceKey="banana-default-piece-ui-test",
                    onMeasuredSave={ _,selection -> saved=selection })
            } }
        }
        compose.onNodeWithText("≈ 118 г").assertExists()
        compose.onNodeWithText("105 ккал").assertExists()
        compose.onNodeWithText("Количество (шт.)").performTextReplacement("2")
        compose.onNodeWithText("≈ 236 г").assertExists()
        compose.onNodeWithText("210 ккал").assertExists()
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(2.0,saved!!.quantity,0.0)
            assertEquals(236.0,saved!!.baseAmount,0.001)
        }
    }

    @Test fun eggSizeChangesCountWithoutDividingByInitialGramBasis() {
        val sizes=listOf(
            FoodMeasure("medium","egg","egg_medium","Среднее",44.0,approximate=true),
            FoodMeasure("large","egg","egg_large","Большое",50.0,
                approximate=true,isDefault=true))
        var saved: ChosenMeasure?=null
        compose.setContent {
            JetmealTheme { Surface {
                AmountContent(name="Яйцо варёное",unit="g",amount=50.0,basisAmount=100.0,
                    nutrition=Nutrition(155.0,12.6,10.6,1.1),
                    estimated=true,busy=false,error=null,onSave={},onDelete=null,
                    onSuccess={},onBack=null,measures=sizes,
                    measurePreferenceKey="egg-size-count-ui-test",
                    onMeasuredSave={ _,selection -> saved=selection })
            } }
        }
        compose.onNodeWithText("Размер яйца").assertExists()
        compose.onNodeWithText("≈ 50 г").assertExists()
        compose.onNodeWithText("Количество (шт.)").performTextReplacement("3")
        compose.onNodeWithText("≈ 150 г").assertExists()
        compose.onNodeWithText("Среднее").performClick()
        compose.onNodeWithText("≈ 132 г").assertExists()
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals(3.0,saved!!.quantity,0.0)
            assertEquals(132.0,saved!!.baseAmount,0.001)
            assertEquals("egg_medium",saved!!.measure.key)
        }
    }

    @Test fun onlyMediumAndLargeEggMeasuresAreOffered() {
        val sizes=listOf(
            FoodMeasure("medium","egg","egg_medium","Среднее",44.0,approximate=true),
            FoodMeasure("large","egg","egg_large","Большое",50.0,approximate=true,isDefault=true))
        compose.setContent {
            JetmealTheme { Surface {
                AmountContent(name="Яйцо куриное",unit="g",amount=100.0,
                    basisAmount=100.0,nutrition=Nutrition(143.0,13.0,10.0,1.0),
                    estimated=true,busy=false,error=null,onSave={},onDelete=null,
                    onSuccess={},onBack=null,measures=sizes,
                    measurePreferenceKey="eggs-ui-test")
            } }
        }
        compose.onNodeWithText("Среднее").assertExists()
        compose.onNodeWithText("Большое").assertExists()
        compose.onNodeWithText("≈",substring=true).assertExists()
    }
    @Test fun sugarUnitPickerOffersIndependentTeaspoonsAndTablespoons() {
        val tsp=FoodMeasure("sugar-tsp","sugar","tsp","ч. л.",4.0,approximate=true)
        val tbsp=FoodMeasure("sugar-tbsp","sugar","tbsp","ст. л.",12.0,approximate=true)
        var saved: ChosenMeasure?=null
        compose.setContent {
            JetmealTheme { Surface {
                AmountContent(name="Сахар белый",unit="g",amount=100.0,basisAmount=100.0,
                    nutrition=Nutrition(400.0,0.0,0.0,100.0),estimated=false,
                    busy=false,error=null,onSave={},onDelete=null,onSuccess={},onBack=null,
                    measures=listOf(tbsp,tsp),measurePreferenceKey="sugar-context-ui-test",
                    onMeasuredSave={ _,selection -> saved=selection })
            } }
        }
        compose.onNodeWithText("Единица измерения").assertExists()
        compose.onNodeWithText("ч. л.").assertExists()
        compose.onNodeWithText("ст. л.").performClick()
        compose.onNodeWithText("Количество (ст. л.)").performTextReplacement("2")
        compose.onNodeWithText("≈ 24 г").assertExists()
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("tbsp",saved!!.measure.key)
            assertEquals(24.0,saved!!.baseAmount,.001)
        }
    }

    @Test fun metricGlassBeginsAtOneAndCanBeLoggedInHalves() {
        val glass=FoodMeasure("glass","milk","cup","стакан 250 мл",254.0,
            approximate=true,isDefault=true)
        var saved: ChosenMeasure?=null
        compose.setContent {
            JetmealTheme { Surface {
                AmountContent(name="Молоко цельное",unit="g",amount=100.0,basisAmount=100.0,
                    nutrition=Nutrition(60.0,3.2,3.2,4.8),estimated=false,
                    busy=false,error=null,onSave={},onDelete=null,onSuccess={},onBack=null,
                    measures=listOf(glass),measurePreferenceKey="milk-glass-ui-test",
                    onMeasuredSave={ _,selection -> saved=selection })
            } }
        }
        compose.onNodeWithText("стакан 250 мл").assertExists()
        compose.onNodeWithText("≈ 254 г").assertExists()
        compose.onNodeWithText("Количество (стакан 250 мл)").performTextReplacement("0,5")
        compose.onNodeWithText("≈ 127 г").assertExists()
        compose.onNodeWithText("Добавить еду").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(127.0,saved!!.baseAmount,.001)
        }
    }

}
