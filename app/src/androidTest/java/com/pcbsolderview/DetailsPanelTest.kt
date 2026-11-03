package com.pcbsolderview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pcbsolderview.model.Board
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import com.pcbsolderview.model.SourceFormat
import com.pcbsolderview.ui.DetailsPanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailsPanelTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun showsComponentDataAndDashesForMissingFields() {
        val c = Component(
            refDes = "R12", value = "10k", description = null, footprint = "R0603",
            side = Side.BOTTOM, position = Point(12_500, 3_000), bounds = Rect(0, 0, 1, 1),
        )
        val board = Board("t", SourceFormat.PICK_AND_PLACE, components = listOf(c))
        rule.setContent { MaterialTheme { DetailsPanel(board, c, onClose = {}) } }

        rule.onNodeWithTag("details.refdes").assertTextEquals("R12")
        rule.onNodeWithTag("details.value").assertTextEquals("10k")
        rule.onNodeWithText("R0603").assertIsDisplayed()
        rule.onNodeWithText("Bottom").assertIsDisplayed()
        rule.onNodeWithText("12.500, 3.000").assertIsDisplayed()
        // description, part number, manufacturer, pads -> "—"
        assertTrue(rule.onAllNodesWithText("—").fetchSemanticsNodes().size >= 4)
    }
}
