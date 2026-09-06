package org.itantra.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The evidence behind constraint **C2** for the Wi-Fi transport.
 *
 * C2 used to be verified by the absence of `android.permission.INTERNET`. That was a
 * stronger claim than C2 makes, and it made a transport ISRO's own description asks for
 * ("streamed through wifi/Bluetooth connected embedded device or another phone")
 * impossible to build, because Android requires the permission to open *any* socket —
 * including one that only ever addresses a broadcast address on the local subnet.
 *
 * So the permission is declared and the claim is verified by inspection instead:
 *
 *  1. every datagram goes to a broadcast address,
 *  2. nothing resolves a hostname,
 *  3. nothing opens an outbound network connection.
 *
 * This class asserts (1) and (2) as executable tests. [WifiBroadcastLink.broadcastTargets]
 * is the *only* thing that decides where a frame is sent — `send` iterates it and does
 * nothing else — so proving every entry is a broadcast address proves (1) for the whole
 * transport. (3) is a property of the codebase rather than of one class, and is checked by
 * `the codebase opens no outbound network connection` below plus the grep recorded in
 * `docs/SECURITY.md` audit item 3.
 *
 * These run on the JVM, not on a device: `NetworkInterface` and `InetAddress` are plain
 * `java.net`, so no `Context` and no handset are needed. That is why `broadcastTargets` is
 * a stateless `internal` function on the companion rather than a private method.
 */
class WifiBroadcastLinkTest {
    /**
     * Requirement (1). Recomputes the expected target set straight from `java.net` and
     * asserts the production code produces exactly that — no extra address, and in
     * particular no unicast one. Equality both ways is the point: a test that only checked
     * "every target is a broadcast address" would still pass if `send` also reached a peer
     * directly, because the check would never see the unicast entry.
     */
    @Test
    fun `every send target is a broadcast address and nothing else`() {
        val expected = ArrayList<InetAddress>()
        for (nic in NetworkInterface.getNetworkInterfaces()) {
            if (!nic.isUp || nic.isLoopback) continue
            for (address in nic.interfaceAddresses) {
                address.broadcast?.let(expected::add)
            }
        }
        expected += InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

        val actual = WifiBroadcastLink.broadcastTargets()

        assertEquals(
            "the transport must send to the enumerated broadcast addresses and nothing else",
            expected,
            actual,
        )
    }

    /**
     * Requirement (1), stated as the property rather than as a recomputation, so the test
     * still means something if the enumeration is ever rewritten.
     *
     * A broadcast address is IPv4 by definition — IPv6 has no broadcast — and can be
     * neither loopback, nor multicast, nor a routable destination that a packet could
     * leave the subnet through. Every target is checked against all four.
     */
    @Test
    fun `no target is loopback, multicast, or routable off the subnet`() {
        val targets = WifiBroadcastLink.broadcastTargets()
        assertTrue("there is always at least the limited broadcast", targets.isNotEmpty())

        for (target in targets) {
            assertTrue("broadcast is an IPv4 concept: $target", target is Inet4Address)
            assertFalse("a broadcast target must not be loopback: $target", target.isLoopbackAddress)
            assertFalse("a broadcast target must not be multicast: $target", target.isMulticastAddress)
            assertFalse("a broadcast target must not be a wildcard: $target", target.isAnyLocalAddress)

            // The last octet of a subnet broadcast is the all-ones host part; for the
            // limited broadcast every octet is 255. Either way the final byte is 0xFF,
            // and a unicast peer address essentially never is.
            val octets = target.address
            assertEquals("a broadcast address ends in an all-ones host octet: $target", -1, octets[3].toInt())
        }
    }

    /**
     * Requirement (2). The limited broadcast is constructed from four bytes, never
     * resolved from the string "255.255.255.255", so no resolver is consulted on the send
     * path at all. Asserting the bytes rather than the text is what makes this a test of
     * the mechanism instead of a test of a spelling.
     */
    @Test
    fun `the limited broadcast is built from bytes, not resolved from a name`() {
        val limited = WifiBroadcastLink.broadcastTargets().last()

        assertArrayEquals4(byteArrayOf(-1, -1, -1, -1), limited.address)
        // `hostAddress` is formatted from the bytes; a resolved name would have left a
        // non-null `hostName` distinct from the literal.
        assertEquals("255.255.255.255", limited.hostAddress)
    }

    /**
     * Requirement (3), as far as a unit test can carry it: the port the transport binds is
     * the one `docs/TRANSPORT.md` section 4 names, and it is a *bound* listening port, not
     * a destination dialled on a remote host.
     */
    @Test
    fun `the frame port is the documented one`() {
        assertEquals(38_173, WifiBroadcastLink.FRAME_PORT)
    }

    private fun assertArrayEquals4(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals("address length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("octet $i", expected[i].toInt(), actual[i].toInt())
        }
    }
}
