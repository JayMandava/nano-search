package ai.nanosearch.launcher.clocks.packs

/** Original data-only fixtures shared by portable parser/archive tests. */
internal object PackSpecFixtures {
    val hour = """{"shape":"needle","color":"#FFFFFFFF","length":0.24,"width":0.024,"tail":0.02}"""
    val minute = """{"shape":"bar","color":"#FFFFFFFF","length":0.39,"width":0.014,"tail":0.03}"""
    val second = """{"shape":"needle","color":"#FFFF7700","length":0.41,"width":0.004,"tail":0.04}"""
    val dial = """{"backgroundColor":"#FF101820","ticks":"minute","tickColor":"#FFE8EDF0","numerals":"quarters","numeralColor":"#FFE8EDF0","font":"system-sans"}"""
    val fields: Map<String, String> = linkedMapOf(
        "format" to quote("nano.clock-face-pack"), "schemaVersion" to "1", "packId" to quote("test-original"),
        "packVersion" to "1", "name" to quote("Original test dial"), "author" to quote("Nano test fixtures"),
        "license" to quote("CC0-1.0"), "minAppVersionCode" to "14", "requires" to "[\"analog-v1\"]",
        "template" to quote("analog-v1"), "dial" to dial, "hands" to "{\"hour\":$hour,\"minute\":$minute}", "assets" to "[]",
    )
    val manifest: String get() = json(fields)
    fun json(values: Map<String, String>): String = values.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value }
    fun changed(vararg changes: Pair<String, String>): String = json(fields + changes)
    fun with(extra: String): String = manifest.dropLast(1) + "," + extra + "}"
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    fun asset(key: String = "texture", hash: String = "0".repeat(64), path: String = "assets/$key.png"): String =
        "{\"key\":${quote(key)},\"path\":${quote(path)},\"sha256\":${quote(hash)}}"
    fun texture(key: String = "texture", hash: String = "0".repeat(64), path: String = "assets/$key.png"): String = changed(
        "requires" to "[\"analog-v1\",\"png-v1\"]", "dial" to dial.dropLast(1) + ",\"textureAsset\":${quote(key)}}",
        "assets" to "[${asset(key, hash, path)}]",
    )
}
