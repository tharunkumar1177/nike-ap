// HidDescriptorParser.kt: minimal HID item walker that derives input report sizes per report ID.
package dev.padlink.hid

/** Result of walking a descriptor: input payload bytes per report ID and collection balance. */
data class DescriptorLayout(val inputBytes: Map<Int, Int>, val collectionsBalanced: Boolean)

/** Walks short HID items, tracking global Report ID/Size/Count to size each input report. */
object HidDescriptorParser {

    /** Parses [descriptor] and returns the input payload size (excluding the ID byte) per report ID. */
    fun parse(descriptor: ByteArray): DescriptorLayout {
        val bits = linkedMapOf<Int, Int>()
        var reportId = 0
        var reportSize = 0
        var reportCount = 0
        var depth = 0
        var balanced = true
        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF
            if (prefix == LONG_ITEM) {
                val dataSize = if (i + 1 < descriptor.size) descriptor[i + 1].toInt() and 0xFF else 0
                i += 3 + dataSize
                continue
            }
            val size = when (prefix and 0x03) { 3 -> 4; else -> prefix and 0x03 }
            val type = (prefix shr 2) and 0x03
            val tag = prefix shr 4
            var value = 0
            for (b in 0 until size) {
                value = value or ((descriptor[i + 1 + b].toInt() and 0xFF) shl (8 * b))
            }
            when (type) {
                TYPE_MAIN -> when (tag) {
                    TAG_INPUT -> bits[reportId] = (bits[reportId] ?: 0) + reportSize * reportCount
                    TAG_COLLECTION -> depth++
                    TAG_END_COLLECTION -> {
                        depth--
                        if (depth < 0) balanced = false
                    }
                }
                TYPE_GLOBAL -> when (tag) {
                    TAG_REPORT_SIZE -> reportSize = value
                    TAG_REPORT_ID -> reportId = value
                    TAG_REPORT_COUNT -> reportCount = value
                }
            }
            i += 1 + size
        }
        if (depth != 0) balanced = false
        return DescriptorLayout(bits.mapValues { (it.value + 7) / 8 }, balanced)
    }

    private const val LONG_ITEM = 0xFE
    private const val TYPE_MAIN = 0
    private const val TYPE_GLOBAL = 1
    private const val TAG_INPUT = 0x8
    private const val TAG_COLLECTION = 0xA
    private const val TAG_END_COLLECTION = 0xC
    private const val TAG_REPORT_SIZE = 0x7
    private const val TAG_REPORT_ID = 0x8
    private const val TAG_REPORT_COUNT = 0x9
}
