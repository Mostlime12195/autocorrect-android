package dev.mostlime.autocorrect.engine

import org.junit.Test
import kotlin.test.assertEquals

class DeviceCpuTest {
    private fun cores(vararg spec: Pair<Int?, Long?>) = spec.mapIndexed { i, (p, f) -> DeviceCpu.Core(i, p, f) }

    @Test fun threadPolicy() {
        // Galaxy A12 (Helio P35): 4x A53 @2.3 + 4x A53 @1.8 -> the fast cluster, 4 threads
        assertEquals(4, DeviceCpu.threadsFor(cores(*Array(4) { 0x41d03 to 2_300_000L }, *Array(4) { 0x41d03 to 1_800_000L })))
        // Snapdragon 8 Gen 2: 3x A510 + 4x A715/A710 + 1x X3 -> 5 big, capped at 4
        assertEquals(4, DeviceCpu.threadsFor(cores(*Array(3) { 0x41d46 to 2_000_000L }, *Array(4) { 0x41d4d to 2_800_000L }, 0x41d4e to 3_200_000L)))
        // Snapdragon 7-series / Dimensity 700: 6x A55 + 2x A76/A78 -> 2
        assertEquals(2, DeviceCpu.threadsFor(cores(*Array(6) { 0x41d05 to 1_800_000L }, *Array(2) { 0x41d41 to 2_400_000L })))
        // Kryo 260: 4 Silver + 4 Gold -> 4
        assertEquals(4, DeviceCpu.threadsFor(cores(*Array(4) { 0x51801 to 1_800_000L }, *Array(4) { 0x51800 to 2_200_000L })))
        // no part ids: frequency clusters (2 fast of 8) -> 2; all the same -> 4
        assertEquals(2, DeviceCpu.threadsFor(cores(*Array(6) { null to 1_800_000L }, *Array(2) { null to 2_400_000L })))
        assertEquals(4, DeviceCpu.threadsFor(cores(*Array(8) { null to 2_000_000L })))
        // nothing readable
        assertEquals(2, DeviceCpu.threadsFor(emptyList()))
        println("this machine: ${DeviceCpu.describe()} -> ${DeviceCpu.intraOpThreads()} threads")
    }
}
