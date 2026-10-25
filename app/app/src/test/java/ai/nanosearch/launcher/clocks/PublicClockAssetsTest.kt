package ai.nanosearch.launcher.clocks

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicClockAssetsTest {
    @Test fun publicAssetsUseLicensedFallbackWithoutThePersonalFont() {
        val assets = File("src/main/assets")
        val names = assets.walkTopDown().filter(File::isFile).map { it.name.lowercase() }.toSet()
        assertFalse("The personal clock font must never enter public assets", names.any { it.contains("porscha") || it.contains("porsche") })
        assertTrue("Steel's public Barlow fallback must remain packaged", File(assets, "Barlow-SemiBold.ttf").length() > 1000)
        assertTrue("Barlow redistribution must retain its OFL", File(assets, "OFL-Barlow.txt").readText().contains("SIL OPEN FONT LICENSE"))
    }

    @Test fun newClockArtworkDoesNotPackageOfficialReferencePhotographs() {
        val assets = File("src/main/assets")
        val names = assets.walkTopDown().filter(File::isFile).map { it.name.lowercase() }.toList()
        assertFalse("Reference photographs are research, not distributable clock assets", names.any { it.contains("richard-mille") || it.contains("mclaren") || it.contains("official-front") || it.contains("official-detail") })
    }
}
