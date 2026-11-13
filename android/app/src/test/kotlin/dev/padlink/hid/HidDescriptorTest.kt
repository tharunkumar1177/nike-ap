// HidDescriptorTest.kt: verifies the combined descriptor yields the contract's report sizes.
package dev.padlink.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HidDescriptorTest {

    /** Input payload sizes match the HID report contract for IDs 1 to 4. */
    @Test
    fun reportSizesMatchContract() {
        val layout = HidDescriptorParser.parse(HidDescriptor.DESCRIPTOR)
        assertEquals(
            mapOf(
                HidDescriptor.ID_KEYBOARD to 7,
                HidDescriptor.ID_MOUSE to 5,
                HidDescriptor.ID_CONSUMER to 2,
                HidDescriptor.ID_PEN to 9,
            ),
            layout.inputBytes,
        )
    }

    /** Every collection is closed. */
    @Test
    fun collectionsBalanced() {
        assertTrue(HidDescriptorParser.parse(HidDescriptor.DESCRIPTOR).collectionsBalanced)
    }

    /** The mouse collection is byte-identical to the original app's descriptor. */
    @Test
    fun mouseCollectionMatchesOriginal() {
        val original = intArrayOf(
            5, 1, 9, 2, -95, 1, -123, 2, 9, 1, -95, 0, 5, 9, 25, 1, 41, 5, 21, 0, 37, 1, 117, 1, -107, 5, -127, 2,
            117, 3, -107, 1, -127, 1, 5, 1, 9, 48, 9, 49, 9, 56, 21, -127, 37, 127, 117, 8, -107, 3, -127, 6, 5, 12,
            10, 56, 2, -107, 1, -127, 6, -64, -64,
        ).map { it.toByte() }
        val descriptor = HidDescriptor.DESCRIPTOR.toList()
        val start = (0..descriptor.size - original.size).first { descriptor.subList(it, it + original.size) == original }
        assertTrue(start > 0)
    }
}
