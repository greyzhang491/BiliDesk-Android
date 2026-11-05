// SPDX-License-Identifier: GPL-3.0-only
package dev.bilidesk.android.data

/** Bounded protobuf reader adapted from the upstream DanmakuParser. Unknown fields are skipped. */
object DanmakuParser {
    private class Reader(val data: ByteArray) {
        var p = 0
        val done get() = p >= data.size
        fun varint(): Long {
            var v = 0L
            for (shift in 0..63 step 7) {
                require(p < data.size) { "Truncated protobuf varint" }
                val b = data[p++].toInt() and 255
                require(shift != 63 || b <= 1) { "Protobuf varint overflow" }
                v = v or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return v
            }
            error("Invalid protobuf varint")
        }
        fun bytes(): ByteArray {
            val n = varint()
            require(n >= 0 && n <= data.size - p) { "Invalid protobuf length" }
            return data.copyOfRange(p, p + n.toInt()).also { p += n.toInt() }
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> { val n = varint(); require(n <= Int.MAX_VALUE && n >= 0); advance(n.toInt()) }
                5 -> advance(4)
                else -> error("Unsupported protobuf wire type")
            }
        }
        fun advance(n: Int) { require(n >= 0 && n <= data.size - p); p += n }
    }
    fun parse(data: ByteArray, maxCount: Int = 12000): List<Danmaku> {
        val r = Reader(data)
        val result = mutableListOf<Danmaku>()
        while (!r.done && result.size < maxCount) {
            val tag = r.varint().toInt()
            if (tag ushr 3 != 1 || tag and 7 != 2) { r.skip(tag and 7); continue }
            val e = Reader(r.bytes())
            var time = 0L; var mode = 1; var color = 0xffffff; var text = ""; var weight = 0
            while (!e.done) {
                val t = e.varint().toInt(); val field = t ushr 3; val wire = t and 7
                when {
                    field == 2 && wire == 0 -> time = e.varint()
                    field == 3 && wire == 0 -> mode = e.varint().toInt()
                    field == 5 && wire == 0 -> color = e.varint().toInt() and 0xffffff
                    field == 7 && wire == 2 -> text = e.bytes().toString(Charsets.UTF_8)
                    field == 9 && wire == 0 -> weight = e.varint().toInt()
                    else -> e.skip(wire)
                }
            }
            if (text.isNotBlank() && time >= 0 && mode in 1..6) result.add(Danmaku(time, text.take(200), mode, color, weight))
        }
        return result.sortedBy { it.timeMs }
    }
}
