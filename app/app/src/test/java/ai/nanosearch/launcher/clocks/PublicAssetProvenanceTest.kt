package ai.nanosearch.launcher.clocks

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exact public provenance, not a filename blacklist: renamed private fonts cannot pass. */
class PublicAssetProvenanceTest {
    private val publicAssets = mapOf(
        "Barlow-SemiBold.ttf" to "86577cb32f8abe3673db53ca0f4221e6856751a4f6730c867e00f720f8bb1fc5",
        "OFL-Barlow.txt" to "186d750eb496a4c17a76385f82be6aea2ac1cf2de074a811d63786cf374ea73f",
        "OFL-SourceSans3.txt" to "09746787287a289323b0ec3cff4d1a4a801331b82b7207c1e186f5d26619a392",
        "SourceSans3.ttf" to "042fe2cc0b933e328410d7acbd0aa6a1873dca5aef81875f4bc214b08825c7b9",
        "brand_icons.tsv" to "c3e2fea2c714875c9e5155181fae078fba547a06edb97eb91f6f33c1554901fd",
        "clip_tokenizer.json" to "72ed5c96db5729294468543e4bc75fce14ca63f58e37300290189ba1c1e52b85",
        "parser.gbnf" to "1519fa0e9bacda943d86eb61592e0cf19cb1863177ca3a6eb194d238f6f1e389",
        "parser_selftest.txt" to "3e342c628ac111bba756d880989cf336478648648758fb06e2cf375dee69bb3c",
        "parser_system.txt" to "fda216bf3b47dcd0ca1df8ab95b65e68b4aeba566280aee3e49b9d643e029ec6",
        "places.tsv" to "c8ce6c8403bea412d1044ccecb0cc3e36a8879f35c289b2c1dd7f6a592913232",
    )
    @Test fun everyPackagedAppAssetMatchesTheReviewedPublicBaseline() {
        val root = File("src/main/assets")
        val actual = root.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(root).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
        assertEquals("Assets must have explicit reviewed public provenance", publicAssets, actual)
    }
}
