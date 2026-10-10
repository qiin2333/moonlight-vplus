package com.limelight.utils.background

import org.json.JSONObject

enum class PipwPool(val jsonKey: String) {
    PC("pc"), PHONE("phone")
}

class PipwReviewIndex private constructor(private val pools: Map<PipwPool, Pool>) {
    class Pool internal constructor(
        val byId: Map<String, String>,
        val byMd5: Map<String, String>
    ) {
        val fallbackFilenames: List<String> = byMd5.values.toList()
    }

    operator fun get(pool: PipwPool): Pool = pools.getValue(pool)

    companion object {
        private val md5Pattern = Regex("[0-9a-f]{32}")
        internal val extensions = setOf("webp", "png", "jpg", "jpeg", "avif", "gif", "bmp")

        fun parse(json: String): PipwReviewIndex {
            val root = JSONObject(json)
            require(root.keys().asSequence().toSet() == setOf("pc", "phone"))
            val pools = PipwPool.entries.associateWith { pool ->
                val records = root.getJSONArray(pool.jsonKey)
                require(records.length() > 0)
                val byId = linkedMapOf<String, String>()
                val byMd5 = linkedMapOf<String, String>()
                val namePattern = Regex("image-${pool.jsonKey}-[0-9]+\\.([a-zA-Z0-9]+)")
                for (i in 0 until records.length()) {
                    val record = records.getJSONObject(i)
                    require(record.keys().asSequence().toSet() == setOf("id", "md5"))
                    val filename = record.get("id") as? String ?: error("Invalid Pipw filename")
                    val md5 = record.get("md5") as? String ?: error("Invalid Pipw digest")
                    val match = namePattern.matchEntire(filename)
                    require(match != null && match.groupValues[1].lowercase() in extensions)
                    require(md5Pattern.matches(md5) && filename !in byId)
                    byId[filename] = md5
                    // Keep every name alias, with one stable fallback per digest.
                    byMd5[md5] = minOf(filename, byMd5[md5] ?: filename)
                }
                Pool(byId.toMap(), byMd5.toMap())
            }
            return PipwReviewIndex(pools)
        }
    }
}
