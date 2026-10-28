package ai.nanosearch.launcher.clocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Arrays
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric/native-Skia geometry checks; no Android emulator or device is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarbonBridgeNativeGeometryTest {
    private fun raster(path: Path, side: Int, assembly: Boolean = true): Region {
        val transformed = Path(path)
        if (assembly) transformed.transform(Matrix().apply {
            setScale(CarbonDialGeometry.ASSEMBLY_SCALE, CarbonDialGeometry.ASSEMBLY_SCALE, 500f, 500f)
        })
        transformed.transform(Matrix().apply { setScale(side / 1000f, side / 1000f) })
        return Region().apply { setPath(transformed, Region(0, 0, side, side)) }
    }

    @Test fun centreBridgeAndLowerCounterHaveExactlyTwoIndependentSupportedComponents() {
        for (side in listOf(240, 360, 720, 1000)) {
            val material = raster(CarbonBridgeGeometry.frame(), side)
            val pieces = components(material, side).sortedByDescending { it.area }
            // This intentional separation replaces the earlier all-connected design. Still
            // reject stray fragments, diagonal-only attachments and deleted support material.
            assertEquals("$side px: main bridge plus independent lower collar, no stray parts", 2, pieces.size)
            val main = pieces[0]; val lower = pieces[1]
            assertTrue("Main 3/9 bridge must be substantial", main.area > side * side / 100)
            assertTrue("Lower collar must be substantial", lower.area > side * side / 500)
            for ((x, y) in listOf(500f to 420f, 456f to 480f, 544f to 480f)) {
                val witness = raster(circle(x, y, 3f), side)
                assertTrue("The main bridge must include its centre and both side collars", main.pixels.any {
                    witness.contains(it % side, it / side)
                })
            }
            val expectedLower = raster(independentLowerCollar(), side)
            val missing = Region(expectedLower).apply { op(material, Region.Op.DIFFERENCE) }
            assertTrue("No part of the lower counter's own collar may be removed", missing.isEmpty)
            assertEquals("Lower collar remains exactly its original support, not a floating fragment", area(expectedLower), lower.area)
            val lowerWitness = raster(circle(500f, 600f, 3f), side)
            assertTrue("Separate component must actually be the six-o'clock collar", lower.pixels.any {
                lowerWitness.contains(it % side, it / side)
            })
        }
    }

    @Test fun removedCentreToSixNeckIsARealOpenGapAtEveryRasterSize() {
        val gap = Path().apply { addRect(484f, 563f, 516f, 595f, Path.Direction.CW) }
        for (side in listOf(240, 360, 720, 1000)) {
            val required = raster(gap, side)
            assertTrue("The neck gap must cover meaningful pixels", area(required) >= side * side / 1500)
            val overlap = Region(required).apply { op(raster(CarbonBridgeGeometry.frame(), side), Region.Op.INTERSECT) }
            assertTrue("The centre octagon must not connect to six o'clock at $side px", overlap.isEmpty)
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

    @Test fun upperSparsSplayOutwardAndLeaveTheCentralCellsOpen() {
        for (side in listOf(1000, 2000)) {
            val material = raster(CarbonBridgeGeometry.frame(), side, assembly = false)
            // These review landmarks distinguish the reference's separated straight spars
            // from the old inward fork; do not move them to fit a regressed silhouette.
            for ((x, y) in listOf(420f to 300f, 410f to 340f, 580f to 300f, 590f to 340f)) {
                val sample = raster(circle(x, y, 2f), side, assembly = false)
                assertFalse("Spar sample must not be vacuous", sample.isEmpty)
                val unsupported = Region(sample).apply { op(material, Region.Op.DIFFERENCE) }
                assertTrue("Straight upper spar missing at $x,$y / $side px", unsupported.isEmpty)
            }
            // The shortened top facet sits ~86 design units above the hand spindle,
            // matching the reference proportion rather than the earlier tall 110-unit arch.
            val facet = raster(circle(500f, 420f, 2f), side, assembly = false)
            assertTrue("Shortened central top facet missing", Region(facet).apply {
                op(material, Region.Op.DIFFERENCE)
            }.isEmpty)
            for (y in listOf(360f, 442f)) {
                val cell = raster(circle(500f, y, 8f), side, assembly = false)
                val overlap = Region(cell).apply { op(material, Region.Op.INTERSECT) }
                assertTrue("The central upper cell must remain open at 500,$y / $side px", overlap.isEmpty)
            }
        }
    }

    @Test fun removedNeckGapContainsNoFrontPaintOrShadow() {
        val image=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888)
        try {
            CarbonBridgeArt().draw(Canvas(image))
            for(y in 570..590) for(x in 486..514) {
                assertEquals("No neck or shadow may paint the middle of the separation",0,image.getPixel(x,y))
            }
            assertEquals("Lower counter still has an opaque top support",255,image.getPixel(500,600) ushr 24)
        } finally { image.recycle() }
    }

    @Test fun graphiteFacesStayOpaqueAfterTheTranslucentShadowPass() {
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        try {
            CarbonBridgeArt().draw(Canvas(bitmap))
            val bareSparSamples = listOf(420 to 300, 580 to 300, 330 to 586, 670 to 586, 402 to 755, 598 to 755, 500 to 420)
            assertEquals("Opacity must exercise seven actual support samples", 7, bareSparSamples.size)
            for ((x, y) in bareSparSamples) {
                assertEquals("Shadow alpha must not leak into the graphite shader at $x,$y", 255,
                    bitmap.getPixel(x, y) ushr 24)
            }
        } finally { bitmap.recycle() }
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

    private fun independentLowerCollar() = Path().apply {
        addCircle(500f, 693f, 97f, Path.Direction.CW)
        check(op(Path().apply {
            addCircle(500f, 693f, 89f, Path.Direction.CW)
            addRect(448f, 765f, 552f, 825f, Path.Direction.CW)
        }, Path.Op.DIFFERENCE))
        check(op(CarbonPusherGeometry.innerDialPath().apply {
            transform(Matrix().apply { setScale(.99f, .99f, 500f, 500f) })
        }, Path.Op.INTERSECT))
    }
    private fun area(region: Region): Int {
        val iterator = RegionIterator(region); val rect = Rect(); var result = 0
        while (iterator.next(rect)) result += rect.width() * rect.height()
        return result
    }
    private fun circle(x: Float, y: Float, radius: Float) = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }
    private data class Component(val area: Int, val firstX: Int, val firstY: Int, val pixels: IntArray)
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
            result += Component(tail, seed % side, seed / side, queue.copyOf(tail))
        }
        return result
    }
}
