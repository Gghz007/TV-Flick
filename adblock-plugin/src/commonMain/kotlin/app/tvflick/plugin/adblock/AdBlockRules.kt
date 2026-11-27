package app.tvflick.plugin.adblock

import kotlinx.serialization.json.*

const val MAX_ADBLOCK_RULE_BYTES = 256 * 1024

/** Data only: no scripts, regex programs, redirects or replacement media URLs. */
data class AdBlockRules(
    val revision: Int,
    val blockedHosts: Set<String>,
    val blockedPaths: Set<String> = emptySet(),
    val sourceIds: Set<String> = emptySet(),
) {
    companion object {
        // Small conservative seed, not a claim of complete EasyList support.
        // These exact ad-server hosts are listed by EasyList; see docs/ADBLOCK_MODULE.md.
        val Bundled = AdBlockRules(1, setOf("ad.doubleclick.net", "fls.doubleclick.net",
            "g.doubleclick.net", "static.doubleclick.net"))

        fun parse(text: String): AdBlockRules {
            require(text.length <= MAX_ADBLOCK_RULE_BYTES && text.encodeToByteArray().size <= MAX_ADBLOCK_RULE_BYTES)
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.keys.all { it in setOf("schemaVersion", "revision", "blockedHosts", "blockedPaths", "sourceIds") })
            require(root["schemaVersion"]?.jsonPrimitive?.int == 1)
            val revision = requireNotNull(root["revision"]?.jsonPrimitive?.int).also { require(it > 0) }
            fun strings(key: String, limit: Int, maxLength: Int): Set<String> {
                val values = root[key]?.jsonArray ?: return emptySet()
                require(values.size <= limit)
                return values.map { value ->
                    val item = value.jsonPrimitive
                    require(item.isString)
                    item.content.also { require(it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl)) }
                }.toSet()
            }
            val hosts = strings("blockedHosts", 4096, 253).map { it.lowercase().trimEnd('.') }.toSet()
            val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
            require(hosts.all { host -> host.contains('.') && host.split('.').all(label::matches) })
            val paths = strings("blockedPaths", 128, 256)
            // Avoid broad '/' rules and URLs disguised as paths. Matches ignore query/fragment.
            require(paths.all { it.startsWith('/') && it.length >= 3 && !it.contains("//") && it.none { c -> c == '?' || c == '#' || c == '\\' } })
            return AdBlockRules(revision, hosts, paths, strings("sourceIds", 128, 256))
        }
    }
}
