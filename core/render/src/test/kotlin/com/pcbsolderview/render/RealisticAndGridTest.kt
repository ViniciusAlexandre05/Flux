package com.pcbsolderview.render

import androidx.compose.ui.graphics.Color
import com.pcbsolderview.model.Component
import com.pcbsolderview.model.Point
import com.pcbsolderview.model.Rect
import com.pcbsolderview.model.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealisticAndGridTest {
    private fun part(ref: String, desc: String? = null, outline: List<Point>? = null) =
        Component(refDes = ref, description = desc, side = Side.TOP, position = Point(0, 0), bounds = Rect(0, 0, 1_000, 500), outline = outline)

    @Test
    fun bodyColoursByType() {
        assertEquals(BodyStyle.ResistorBlack, BodyStyle.colorFor(part("R12")))
        assertEquals(BodyStyle.CeramicTan, BodyStyle.colorFor(part("C3", "CAP CER 1UF")))
        assertEquals(BodyStyle.IcBlack, BodyStyle.colorFor(part("U1")))
        assertEquals(BodyStyle.TerminalBlue, BodyStyle.colorFor(part("J2", "2 Position Wire to Board Terminal Block")))
        assertEquals(BodyStyle.LedWhite, BodyStyle.colorFor(part("D1", "Red LED")))
        assertEquals(BodyStyle.Default, BodyStyle.colorFor(part("TP4")))
        // Connectors named by function are recognised from the description.
        assertEquals(BodyStyle.TerminalBlue, BodyStyle.colorFor(part("24V_IN", "2 Position Wire to Board Terminal Block Horizontal")))
        assertEquals(BodyStyle.ConnectorBeige, BodyStyle.colorFor(part("J5", "Header 2x5")))
    }

    @Test
    fun bodyShapeUsesOutlineOrInsetBox() {
        val o = listOf(Point(1, 1), Point(9, 1), Point(9, 4))
        assertEquals(o, BodyStyle.shapeFor(part("U1", outline = o)))
        val inset = Rect.of(BodyStyle.shapeFor(part("R1")))
        assertEquals(Rect(120, 60, 880, 440), inset)
    }

    @Test
    fun silkReadableOnMask() {
        assertEquals(Color(0xFF1A1A1A), BodyStyle.silkFor(Color(0xFFE9EAE6))) // white mask -> black ink
        assertEquals(Color(0xFFF2F2EE), BodyStyle.silkFor(Color(0xFF1E5C3A))) // green mask -> white ink
    }

    @Test
    fun copperToneIsDarkerOnColouredMaskAndVisibleOnBlack() {
        val green = Color(0xFF1E5C3A)
        val tone = BodyStyle.copperUnderMask(green)
        assertTrue(tone.green < green.green && tone.red <= green.red && tone.blue <= green.blue) // darker, same hue
        val black = Color(0xFF141414)
        assertTrue(BodyStyle.copperUnderMask(black).red > black.red) // lighter, so tracks still show
    }

    @Test
    fun manualGridSpacingAndUnits() {
        assertEquals(250L, Grid.toMicrons(0.25, GridUnit.MM))
        assertEquals(1_270L, Grid.toMicrons(50.0, GridUnit.MIL))
        // Switching units picks the closest choice: 1 mm ~ 50 mil (1.27 mm); 100 mil (2.54 mm) ~ 2.5 mm.
        assertEquals(50.0, Grid.MIL_STEPS[Grid.nearestIndex(1_000, GridUnit.MIL)])
        assertEquals(2.5, Grid.MM_STEPS[Grid.nearestIndex(2_540, GridUnit.MM)])
        assertEquals("0.25 mm", Grid.label(0.25, GridUnit.MM))
        assertEquals("100 mil", Grid.label(100.0, GridUnit.MIL))
        // Fixed spacing: all lines when far enough apart, only every 5th when dense, nothing when useless.
        assertEquals(Grid.Density.ALL, Grid.density(1_000, 0.02))     // 20 px
        assertEquals(Grid.Density.MAJOR, Grid.density(1_000, 0.004))  // 4 px, majors 20 px
        assertEquals(Grid.Density.NOTHING, Grid.density(1_000, 0.001)) // majors 5 px
    }

    @Test
    fun autoGridStepAdaptsToZoom() {
        assertEquals(100L, Grid.autoStep(1.0))          // 1 px/µm: 0.1 mm = 100 px
        assertEquals(1_000L, Grid.autoStep(0.03))      // 1 mm = 30 px
        assertEquals(2_500L, Grid.autoStep(0.01))      // 1 mm = 10 px -> too dense; 2.5 mm = 25 px
        assertTrue(Grid.isMajor(0) && Grid.isMajor(-5) && !Grid.isMajor(3))
        assertEquals(-2L..3L, Grid.range(-1_500.0, 2_100.0, 1_000))
    }

    @Test
    fun visibleAreaCoversRotatedViewport() {
        val t = ViewTransform.fitted(Rect(0, 0, 100_000, 50_000), 1000.0, 500.0, paddingPx = 0.0).rotateBy(30.0, 500.0, 250.0)
        val v = Grid.visible(t, 1000.0, 500.0)
        // Every screen corner maps inside the reported board rectangle.
        for ((sx, sy) in listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 500.0, 1000.0 to 500.0)) {
            val (x, y) = t.toBoard(sx, sy)
            assertTrue(x >= v[0] - 1e-6 && x <= v[2] + 1e-6 && y >= v[1] - 1e-6 && y <= v[3] + 1e-6)
        }
    }
}
