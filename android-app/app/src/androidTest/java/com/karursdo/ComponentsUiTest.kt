package com.karursdo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karursdo.ui.components.FieldRow
import com.karursdo.ui.components.SectionCard
import com.karursdo.ui.components.StatusChip
import com.karursdo.ui.components.TypePill
import com.karursdo.ui.components.initialsOf
import com.karursdo.ui.theme.KarurSdoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sectionCard_rendersTitleAndFields() {
        compose.setContent {
            KarurSdoTheme {
                SectionCard("Bank") {
                    FieldRow("Account no.", "0000111122")
                    FieldRow("IFSC", null)
                }
            }
        }
        compose.onNodeWithText("Bank").assertIsDisplayed()
        compose.onNodeWithText("Account no.").assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun pills_renderCadreAndStatus() {
        compose.setContent {
            KarurSdoTheme {
                androidx.compose.foundation.layout.Column {
                    TypePill("GDS")
                    StatusChip("Paid")
                }
            }
        }
        compose.onNodeWithText("GDS").assertIsDisplayed()
        compose.onNodeWithText("Paid").assertIsDisplayed()
    }

    @Test
    fun initials_matchWebAppRule() {
        assertEquals("MS", initialsOf("Manivel Selvam"))
        assertEquals("R", initialsOf("RAJESWARI"))
        assertEquals("?", initialsOf(""))
    }
}
