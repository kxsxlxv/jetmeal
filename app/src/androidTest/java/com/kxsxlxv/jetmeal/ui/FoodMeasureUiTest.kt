package com.kxsxlxv.jetmeal.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
