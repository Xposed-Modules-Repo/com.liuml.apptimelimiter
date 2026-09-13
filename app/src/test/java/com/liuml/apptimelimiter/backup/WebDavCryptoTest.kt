package com.liuml.apptimelimiter.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDavCryptoTest {
    @Test fun roundTripAndWrongPasswordFails() {
        val encrypted = WebDavCrypto.encrypt("portable backup", "secret-pass".toCharArray())
        assertEquals("portable backup", WebDavCrypto.decrypt(encrypted, "secret-pass".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) {
            WebDavCrypto.decrypt(encrypted, "wrong-pass".toCharArray())
        }
    }

    @Test fun malformedEnvelopeFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavCrypto.decrypt("TSWD1.bad", "secret".toCharArray())
        }
    }
}
