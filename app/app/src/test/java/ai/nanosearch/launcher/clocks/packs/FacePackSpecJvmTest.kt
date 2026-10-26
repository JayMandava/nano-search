package ai.nanosearch.launcher.clocks.packs

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class FacePackSpecJvmTest {
    private val fixture get() = PackSpecFixtures.manifest
    private fun parse(source: String) = FacePackSpec.parse(source.toByteArray(Charsets.UTF_8))
    private fun rejected(source: String): FacePackException {
        try { parse(source); fail("Unsafe manifest was accepted") } catch (expected: FacePackException) { return expected }
        throw AssertionError("Unreachable")
    }
    private fun rejectBytes(bytes: ByteArray) {
        try { FacePackSpec.parse(bytes); fail("Unsafe bytes were accepted") } catch (_: FacePackException) { }
    }
    private fun length(value: String) = fixture.replaceFirst("\"length\":0.24", "\"length\":$value")
    private fun width(value: String) = fixture.replaceFirst("\"width\":0.024", "\"width\":$value")
    private fun tail(value: String) = fixture.replaceFirst("\"tail\":0.02", "\"tail\":$value")

    @Test fun strictAnalogFixtureHasExpectedPortableModel() {
        val spec = parse(fixture)
        assertEquals("nano.clock-face-pack", spec.format)
        assertEquals(1, spec.schemaVersion); assertEquals("test-original", spec.packId); assertEquals(1, spec.packVersion)
        assertEquals("Nano test fixtures", spec.author); assertEquals("CC0-1.0", spec.license); assertEquals(14, spec.minAppVersionCode)
        assertEquals(FacePackTicks.MINUTE, spec.dial.ticks); assertEquals(FacePackNumerals.QUARTERS, spec.dial.numerals)
        assertEquals(FacePackFont.SYSTEM_SANS, spec.dial.font); assertEquals(FacePackHandShape.NEEDLE, spec.hands.hour.shape)
        assertEquals(0.24f, spec.hands.hour.length, 0f); assertEquals(0.02f, spec.hands.hour.tail, 0f)
        assertNull(spec.description); assertNull(spec.hands.second); assertNull(spec.dateWindow); assertNull(spec.dial.textureAsset)
        assertTrue(spec.assets.isEmpty()); assertTrue(spec.assetNames().isEmpty())
    }

    @Test fun optionalSecondDateAndDescriptionAreNativeDataOnly() {
        val spec = parse(PackSpecFixtures.changed(
            "description" to "\"Original fixture\"",
            "hands" to "{\"hour\":${PackSpecFixtures.hour},\"minute\":${PackSpecFixtures.minute},\"second\":${PackSpecFixtures.second}}",
            "dateWindow" to """{"position":"right","textColor":"#FF010203","backgroundColor":"#00000000"}""",
        ))
        assertNotNull(spec.hands.second); assertEquals(FacePackDatePosition.RIGHT, spec.dateWindow?.position)
        assertEquals(0, spec.dateWindow?.backgroundColor); assertEquals("Original fixture", spec.description)
        assertEquals("", parse(PackSpecFixtures.changed("description" to "\"\"")).description)
    }

    @Test fun declaredTextureIsBoundToAnExactPathDigestAndCapability() {
        val spec = parse(PackSpecFixtures.texture())
        assertEquals("texture", spec.dial.textureAsset)
        assertEquals(setOf("assets/texture.png"), spec.assetNames())
        assertEquals("0".repeat(64), spec.assets.single().sha256)
        assertEquals(listOf("analog-v1", "png-v1"), spec.requires)
    }

    @Test fun parsedListsCannotBeMutatedAfterValidation() {
        val spec = parse(PackSpecFixtures.texture())
        try { (spec.requires as MutableList<String>).add("code-v1"); fail("Mutable capabilities") } catch (_: UnsupportedOperationException) { }
        try { (spec.assets as MutableList<FacePackAsset>).clear(); fail("Mutable assets") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun oldWorkInProgressSchemaAndExecutableFieldsAreNotCompatible() {
        rejected("""{"schemaVersion":1,"template":"analog-v1","id":"test-original","version":1,"name":"Old draft","palette":{},"gears":[]}""")
        for (field in listOf("gears", "palette", "pivot", "script", "class", "intent", "network", "shader", "preview"))
            rejected(PackSpecFixtures.with("\"$field\":[]"))
    }

    @Test fun requiredTopLevelFieldsCannotBeOmitted() {
        for (key in PackSpecFixtures.fields.keys) rejected(PackSpecFixtures.json(PackSpecFixtures.fields - key))
    }

    @Test fun unknownFieldsAreRejectedAtEverySchemaObject() {
        rejected(PackSpecFixtures.with("\"unknown\":1"))
        rejected(PackSpecFixtures.changed("dial" to PackSpecFixtures.dial.dropLast(1) + ",\"x\":1}"))
        rejected(PackSpecFixtures.changed("hands" to "{\"hour\":${PackSpecFixtures.hour},\"minute\":${PackSpecFixtures.minute},\"x\":1}"))
        rejected(fixture.replaceFirst("\"length\":0.24", "\"x\":1,\"length\":0.24"))
        rejected(PackSpecFixtures.with(""""dateWindow":{"position":"right","textColor":"#FFFFFFFF","backgroundColor":"#FF000000","x":1}"""))
        rejected(PackSpecFixtures.texture().replace("\"key\":\"texture\"", "\"x\":1,\"key\":\"texture\""))
    }

    @Test fun duplicateAndEscapedDuplicateKeysAreRejected() {
        rejected(PackSpecFixtures.with("\"packVersion\":2"))
        rejected(PackSpecFixtures.with("\"na\\u006de\":\"duplicate\""))
        rejected(fixture.replaceFirst("\"tail\":0.02", "\"tail\":0.02,\"tail\":0.03"))
    }

    @Test fun unsupportedFormatSchemaTemplateAndCapabilitiesFailClosed() {
        assertFalse(rejected(PackSpecFixtures.changed("format" to "\"other-format\"")).incompatible)
        assertTrue(rejected(PackSpecFixtures.changed("requires" to "[\"analog-v1\",\"future-v2\"]")).incompatible)
        assertTrue(rejected(PackSpecFixtures.changed("schemaVersion" to "2")).incompatible)
        assertTrue(rejected(PackSpecFixtures.changed("template" to "\"gmt-v1\"")).incompatible)
        for (requires in listOf("[]", "[\"png-v1\"]", "[\"analog-v1\",\"analog-v1\"]", "[\"analog-v1\",\"shader-v1\"]", "[true]", "null", "\"analog-v1\""))
            rejected(PackSpecFixtures.changed("requires" to requires))
    }

    @Test fun nullAndWrongTypesNeverMeanAbsentOrCoerced() {
        for ((field, value) in listOf("packVersion" to "\"1\"", "name" to "true", "dial" to "[]", "hands" to "null", "assets" to "{}", "author" to "1"))
            rejected(PackSpecFixtures.changed(field to value))
        for (optional in listOf("description", "dateWindow")) rejected(PackSpecFixtures.changed(optional to "null"))
        rejected(PackSpecFixtures.changed("dial" to PackSpecFixtures.dial.dropLast(1) + ",\"textureAsset\":null}"))
        rejected(PackSpecFixtures.changed("hands" to "{\"hour\":${PackSpecFixtures.hour},\"minute\":${PackSpecFixtures.minute},\"second\":null}"))
        rejected(length("\"0.24\""))
    }

    @Test fun integerVersionsRequirePositiveBoundedIntegerTokens() {
        for (field in listOf("packVersion", "minAppVersionCode")) {
            assertEquals(Int.MAX_VALUE, if (field == "packVersion") parse(PackSpecFixtures.changed(field to Int.MAX_VALUE.toString())).packVersion else parse(PackSpecFixtures.changed(field to Int.MAX_VALUE.toString())).minAppVersionCode)
            for (bad in listOf("0", "-1", "1.0", "1e0", "1.5", "2147483648", "999999999999999999999999")) rejected(PackSpecFixtures.changed(field to bad))
        }
    }

    @Test fun allGeometryLimitsAreInclusiveAndNotClamped() {
        for (good in listOf("0.05", "0.0500001", "0.48")) assertTrue(parse(length(good)).hands.hour.length in 0.05f..0.48f)
        for (bad in listOf("0.04999999999999999999", "0.48000000000000000001", "0", "-0.01", "1")) rejected(length(bad))
        for (good in listOf("0.001", "0.08")) assertTrue(parse(width(good)).hands.hour.width in 0.001f..0.08f)
        for (bad in listOf("0.0009999999", "0.08000000000000000001", "-1")) rejected(width(bad))
        for (good in listOf("0", "0.15")) assertTrue(parse(tail(good)).hands.hour.tail in 0f..0.15f)
        for (bad in listOf("-0.00001", "0.15000000000000000001", "1e-50")) rejected(tail(bad))
    }

    @Test fun strictNumberGrammarAndNonfiniteValuesAreRejected() {
        for (bad in listOf("NaN", "Infinity", "-Infinity", "+0.24", ".24", "0.24.", "00.24", "0x1", "1e", "1e+", "-", "1e999", "1e-999", "1e2147483648", "1".repeat(65))) rejected(length(bad))
        assertEquals(0.24f, parse(length("2.4e-1")).hands.hour.length, 0f)
    }

    @Test fun colorsMustBeEightHexDigitArgb() {
        for (bad in listOf("#101820", "#FFF", "#GG101820", "0xFF101820", "#FF1018200", "red")) rejected(fixture.replace("#FF101820", bad))
        assertEquals(0x7fabcdef, parse(fixture.replace("#FF101820", "#7fAbCdEf")).dial.backgroundColor)
    }

    @Test fun displayLimitsCountUnicodeCodePointsNotUtf16Units() {
        for ((field, limit) in listOf("name" to 60, "author" to 80, "license" to 80, "description" to 240)) {
            for (size in listOf(limit - 1, limit)) parse(PackSpecFixtures.changed(field to PackSpecFixtures.quote("😀".repeat(size))))
            rejected(PackSpecFixtures.changed(field to PackSpecFixtures.quote("😀".repeat(limit + 1))))
        }
        for (field in listOf("name", "author", "license")) for (blank in listOf("", "   ")) rejected(PackSpecFixtures.changed(field to PackSpecFixtures.quote(blank)))
    }

    @Test fun displayControlsBidiMarkupAndLinksAreRejected() {
        val bad = listOf("bad\u0000name", "bad\u007fname", "bad\u0085name", "bad\u202ename", "bad\u2066name", "bad\u200bname", "bad\u2028name", "<b>name</b>", "[name](https://example.com)", "https://example.com", "MAILTO:a@example.com", "www.example.com", "example.com", "**bold**", "`code`")
        for (field in listOf("name", "author", "license", "description")) for (value in bad) rejected(PackSpecFixtures.changed(field to PackSpecFixtures.quote(value)))
        assertEquals("Clock: Blue", parse(PackSpecFixtures.changed("name" to "\"Clock: Blue\"")).name)
    }

    @Test fun unicodeEscapesRequirePairedSurrogatesAndAsciiHexDigits() {
        assertEquals("😀", parse(PackSpecFixtures.changed("name" to "\"\\uD83D\\uDE00\"")).name)
        for (bad in listOf("\\uD800", "\\uDC00", "\\uD800x", "\\uD800\\uD800", "\\uDC00\\uD800", "\\u１２３４", "\\uZZZZ", "\\q", "\\u123"))
            rejected(PackSpecFixtures.changed("name" to "\"$bad\""))
    }

    @Test fun jsonSyntaxRejectsTrailingCommentsAndNonJsonWhitespace() {
        for (bad in listOf("$fixture {}", "$fixture true", "/*x*/$fixture", "//x\n$fixture", "\uFEFF$fixture", "\u00A0$fixture", fixture.dropLast(1) + ",}", fixture.replace("[\"analog-v1\"]", "[\"analog-v1\",]"), fixture.replaceFirst("\"format\"", "'format'"))) rejected(bad)
        assertEquals("test-original", parse(" \t\r\n$fixture \t\r\n").packId)
    }

    @Test fun invalidUtf8AndManifestByteLimitFailBeforeParsing() {
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) rejectBytes(bytes)
        val size = fixture.toByteArray().size
        for (limit in listOf(FacePackLimits.MAX_MANIFEST_BYTES - 1, FacePackLimits.MAX_MANIFEST_BYTES)) parse(fixture + " ".repeat(limit - size))
        rejected(fixture + " ".repeat(FacePackLimits.MAX_MANIFEST_BYTES + 1 - size))
    }

    @Test fun depthAndValueCountersHaveExactBoundaries() {
        assertFalse(rejected("[".repeat(8) + "0" + "]".repeat(8)).message!!.contains("complexity"))
        assertTrue(rejected("[".repeat(9) + "0" + "]".repeat(9)).message!!.contains("complexity"))
        assertFalse(rejected(List(1023) { "0" }.joinToString(",", "[", "]")).message!!.contains("complexity"))
        assertTrue(rejected(List(1024) { "0" }.joinToString(",", "[", "]")).message!!.contains("complexity"))
    }

    @Test fun packIdentifiersHaveExactGrammarBoundsAndReservedNames() {
        for (id in listOf("a", "a" + "b".repeat(63))) assertEquals(id, parse(PackSpecFixtures.changed("packId" to PackSpecFixtures.quote(id))).packId)
        for (id in listOf("", "1clock", "Clock", "a_b", "../a", "a:b", "a,b", "a".repeat(65), "steeldial", "carbon01")) rejected(PackSpecFixtures.changed("packId" to PackSpecFixtures.quote(id)))
    }

    @Test fun assetReferencesMustBeExactUniqueAndUsed() {
        rejected(PackSpecFixtures.texture().replace("\"analog-v1\",\"png-v1\"", "\"analog-v1\""))
        rejected(PackSpecFixtures.texture().replace("\"textureAsset\":\"texture\"", "\"textureAsset\":\"missing\""))
        rejected(PackSpecFixtures.changed("assets" to "[${PackSpecFixtures.asset()}]", "requires" to "[\"analog-v1\",\"png-v1\"]"))
        val duplicate = PackSpecFixtures.texture().replace("\"assets\":[${PackSpecFixtures.asset()}]", "\"assets\":[${PackSpecFixtures.asset()},${PackSpecFixtures.asset()}]")
        rejected(duplicate)
        rejected(PackSpecFixtures.texture().replace("\"assets\":[${PackSpecFixtures.asset()}]", "\"assets\":[${PackSpecFixtures.asset()},${PackSpecFixtures.asset("unused")} ]"))
    }

    @Test fun assetGrammarHashAndFourEntryCeilingFailClosed() {
        parse(PackSpecFixtures.texture("a" + "b".repeat(31)))
        for (key in listOf("a".repeat(33), "1texture", "Texture", "a_b", "../x")) rejected(PackSpecFixtures.texture(key))
        for (path in listOf("texture.png", "assets/../texture.png", "/assets/texture.png", "assets/texture.PNG", "assets/%74exture.png", "assets/texture.svg", "assets\\texture.png")) rejected(PackSpecFixtures.texture(path = path))
        for (hash in listOf("a".repeat(63), "a".repeat(65), "A".repeat(64), "z".repeat(64))) rejected(PackSpecFixtures.texture(hash = hash))
        val assets = (1..5).joinToString(",") { PackSpecFixtures.asset("texture-$it") }
        assertTrue(rejected(PackSpecFixtures.changed("assets" to "[$assets]")).message!!.contains("Too many"))
    }

    @Test fun enumsAndOptionalObjectsHaveFixedCompleteSchemas() {
        for (tick in listOf("none", "hour", "minute")) parse(fixture.replace("\"ticks\":\"minute\"", "\"ticks\":\"$tick\""))
        for (numerals in listOf("none", "quarters", "all")) parse(fixture.replace("\"numerals\":\"quarters\"", "\"numerals\":\"$numerals\""))
        for (font in listOf("system-sans", "system-condensed", "system-mono")) parse(fixture.replace("system-sans", font))
        for (bad in listOf(fixture.replace("system-sans", "assets/font.ttf"), fixture.replace("\"needle\"", "\"sword\""), fixture.replace("\"minute\"", "\"unsupported\""))) rejected(bad)
        for (date in listOf("{}", "{\"position\":\"right\"}", "{\"position\":\"center\",\"textColor\":\"#FFFFFFFF\",\"backgroundColor\":\"#FF000000\"}")) rejected(PackSpecFixtures.changed("dateWindow" to date))
    }

    @Test fun everyTruncatedPrefixAndBoundedRandomInputFailsWithTypedError() {
        for (end in fixture.indices) rejected(fixture.substring(0, end))
        val random = Random(0x4e414e4f)
        repeat(250) { val bytes = ByteArray(random.nextInt(512)); random.nextBytes(bytes); rejectBytes(bytes) }
    }
}
