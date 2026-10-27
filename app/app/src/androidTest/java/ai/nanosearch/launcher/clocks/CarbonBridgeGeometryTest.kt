package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Arrays
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native geometry checks, independent of optional screenshot capture or visual approval. */
@RunWith(AndroidJUnit4::class)
class CarbonBridgeGeometryTest {
    private fun raster(path: Path, side: Int, assembly: Boolean = true): Region {
        val transformed = Path(path)
        if (assembly) transformed.transform(Matrix().apply {
            setScale(CarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 500f, 500f)
        })
        transformed.transform(Matrix().apply { setScale(side / 1000f, side / 1000f) })
        return Region().apply { setPath(transformed, Region(0, 0, side, side)) }
    }

    @Test fun frameIsOneConnectedSupportAtDesignAndPhoneRasterSizes() {
        for (side in listOf(240, 360, 720, 1000)) {
            val material = raster(CarbonBridgeGeometry.frame(), side)
            assertFalse("The bridge cannot be an empty/vacuously connected path", material.isEmpty)
            val components = components(material, side)
            // Four-neighbor connectivity deliberately rejects diagonal-only, zero-width contacts.
            assertEquals("$side px: material must be one four-neighbor component, got $components", 1, components.size)
            assertTrue("$side px: the check must cover substantial real support material", components.single().area > side * side / 100)
        }
    }

    @Test fun counterDateAndCentralPivotAperturesStayCompletelyOpen() {
        val clearances = listOf(
            "left counter" to circle(350f, 465f, 102f),
            "right counter" to circle(650f, 465f, 102f),
            "lower counter" to circle(500f, 693f, 88f),
            "date" to Path().apply { addRoundRect(333f, 263f, 391f, 364f, 7f, 7f, Path.Direction.CW) },
            "central pivot" to circle(500f, 500f, 7f),
        )
        for (side in listOf(240, 360, 1000)) {
            val material = raster(CarbonBridgeGeometry.frame(), side)
            for ((name, path) in clearances) {
                val opening = raster(path, side)
                assertFalse("$name clearance must exercise real pixels at $side px", opening.isEmpty)
                val overlap = Region(material).apply { op(opening, Region.Op.INTERSECT) }
                assertTrue("Support invades $name clearance at $side px: ${overlap.bounds}", overlap.isEmpty)
            }
            val outside = Region(material).apply { op(raster(CarbonPusherGeometry.innerDialPath(), side), Region.Op.DIFFERENCE) }
            assertTrue("The support must remain inside the unchanged dial envelope at $side px: ${outside.bounds}", outside.isEmpty)
        }
    }

    @Test fun allSixAnchorScrewsHaveFullSeatsOnConnectedSupportMaterial() {
        val expectedCenters = listOf(420f to 300f, 580f to 300f, 330f to 586f, 670f to 586f, 402f to 755f, 598f to 755f)
        val bosses = CarbonBridgeGeometry.bosses
        assertEquals("Anchor checks must not pass after anchors are deleted", 6, bosses.size)
        for ((index, boss) in bosses.withIndex()) {
            assertEquals(expectedCenters[index].first, boss.x, 0f)
            assertEquals(expectedCenters[index].second, boss.y, 0f)
            assertTrue("Every visible screw needs a real support boss", boss.screwRadius > 0f && boss.radius >= boss.screwRadius + 3f)
            for (side in listOf(1000, 2000)) {
                // Seat center radius is screwRadius+2.4; its 1.2-wide stroke extends another 0.6.
                // The boss itself may meet an intentional aperture; the actual entire seat may not.
                val seat = raster(circle(boss.x, boss.y, boss.screwRadius + 3f), side, assembly = false)
                assertFalse(seat.isEmpty)
                val unsupported = Region(seat).apply { op(raster(CarbonBridgeGeometry.frame(), side, assembly = false), Region.Op.DIFFERENCE) }
                assertTrue("Anchor $index has a floating/unsupported screw seat at $side px: ${unsupported.bounds}", unsupported.isEmpty)
            }
        }
    }

    @Test fun frameConstructionIsDeterministicAndReturnedPathsAreIndependent() {
        val first = CarbonBridgeGeometry.frame()
        val expected = raster(first, 1000)
        assertFalse(expected.isEmpty)
        val difference = Region(expected).apply { op(raster(CarbonBridgeGeometry.frame(), 1000), Region.Op.XOR) }
        assertTrue("Repeated native Path unions must produce identical material", difference.isEmpty)
        first.reset()
        first.addRect(0f, 0f, 1000f, 1000f, Path.Direction.CW)
        val afterCallerMutation = Region(expected).apply { op(raster(CarbonBridgeGeometry.frame(), 1000), Region.Op.XOR) }
        assertTrue("One caller must not mutate a shared cached support Path", afterCallerMutation.isEmpty)
    }

    @Suppress("DEPRECATION")
    @Test fun staticBridgeDrawingIsDeterministicAndPreservesCanvasTransformAndClip() {
        val art = CarbonBridgeArt()
        fun draw(side: Int, renderer: CarbonBridgeArt): Bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.scale(side / 1000f, side / 1000f)
            canvas.clipRect(190f, 200f, 810f, 830f)
            val beforeMatrix = Matrix().also(canvas::getMatrix)
            val beforeClip = Rect().also { canvas.getClipBounds(it) }
            val beforeSaveCount = canvas.saveCount
            renderer.draw(canvas)
            val afterMatrix = Matrix().also(canvas::getMatrix)
            assertEquals("Bridge draw cannot move later counters or hands", beforeMatrix, afterMatrix)
            assertEquals("Bridge draw cannot leak its grain clip", beforeClip, Rect().also { canvas.getClipBounds(it) })
            assertEquals("Bridge draw must balance every canvas save", beforeSaveCount, canvas.saveCount)
        }
        for (side in listOf(240, 360, 720)) {
            val first = draw(side, art)
            val repeated = draw(side, art)
            val rebuilt = draw(side, CarbonBridgeArt())
            try {
                assertTrue("A static bridge cannot change between equal frames", first.sameAs(repeated))
                assertTrue("Fresh bridge construction cannot change its fixed artwork", first.sameAs(rebuilt))
            } finally { first.recycle(); repeated.recycle(); rebuilt.recycle() }
        }
    }

    private fun circle(x: Float, y: Float, radius: Float) = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }
    private data class Component(val area: Int, val firstX: Int, val firstY: Int)
    private fun components(region: Region, side: Int): List<Component> {
        val cells = ByteArray(side * side)
        val iterator = RegionIterator(region)
        val rectangle = Rect()
        var count = 0
        while (iterator.next(rectangle)) for (y in rectangle.top until rectangle.bottom) {
            Arrays.fill(cells, y * side + rectangle.left, y * side + rectangle.right, 1.toByte())
            count += rectangle.width()
        }
        val queue = IntArray(count)
        val result = mutableListOf<Component>()
        for (seed in cells.indices) {
            if (cells[seed] != 1.toByte()) continue
            var head = 0; var tail = 1
            queue[0] = seed; cells[seed] = 2
            while (head < tail) {
                val at = queue[head++]
                fun visit(next: Int) { if (cells[next] == 1.toByte()) { cells[next] = 2; queue[tail++] = next } }
                val x = at % side
                if (x > 0) visit(at - 1)
                if (x + 1 < side) visit(at + 1)
                if (at >= side) visit(at - side)
                if (at < cells.size - side) visit(at + side)
            }
            result += Component(tail, seed % side, seed / side)
        }
        return result
    }
}
