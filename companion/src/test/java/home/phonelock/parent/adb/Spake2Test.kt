package home.phonelock.parent.adb

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Our SPAKE2 must agree with an independent implementation of BoringSSL's exchange (spake2-java, test-only)
 * in both roles: if any constant, the password fix-up or the transcript differed, the keys would differ.
 */
class Spake2Test {
    private val client = "adb pair client\u0000".toByteArray()
    private val server = "adb pair server\u0000".toByteArray()
    private val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }

    @Test
    fun oursAsClientAgreesWithReferenceServer() {
        repeat(5) {
            val ours = Spake2(alice = true, myName = client, theirName = server)
            val ref = Spake2Context(Spake2Role.Bob, server, client)
            val ourMsg = ours.generateMessage(password)
            val refMsg = ref.generateMessage(password)
            val ourKey = assertNotNullKey(ours.processMessage(refMsg))
            val refKey = ref.processMessage(ourMsg)
            assertArrayEquals(refKey, ourKey)
        }
    }

    @Test
    fun oursAsServerAgreesWithReferenceClient() {
        repeat(5) {
            val ours = Spake2(alice = false, myName = server, theirName = client)
            val ref = Spake2Context(Spake2Role.Alice, client, server)
            val refMsg = ref.generateMessage(password)
            val ourMsg = ours.generateMessage(password)
            val ourKey = assertNotNullKey(ours.processMessage(refMsg))
            assertArrayEquals(ref.processMessage(ourMsg), ourKey)
        }
    }

    @Test
    fun wrongPasswordGivesDifferentKeys() {
        val ours = Spake2(alice = true, myName = client, theirName = server)
        val ref = Spake2Context(Spake2Role.Bob, server, client)
        val ourMsg = ours.generateMessage(password)
        val refMsg = ref.generateMessage("654321".toByteArray())
        val ourKey = assertNotNullKey(ours.processMessage(refMsg))
        assertFalse(ourKey.contentEquals(ref.processMessage(ourMsg)))
    }

    @Test
    fun ourTwoRolesAgree() {
        val a = Spake2(alice = true, myName = client, theirName = server)
        val b = Spake2(alice = false, myName = server, theirName = client)
        val am = a.generateMessage(password)
        val bm = b.generateMessage(password)
        assertArrayEquals(a.processMessage(bm), b.processMessage(am))
    }

    private fun assertNotNullKey(k: ByteArray?): ByteArray {
        assertNotNull(k)
        return k!!
    }
}
