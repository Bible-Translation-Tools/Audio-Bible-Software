package org.bibletranslationtools.otter.common.domain.wacs.auth

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BasicAuthenticatorTest {
    private val root = createTempDirectory("wacs-auth").toFile()
    private val wacs = FakeWacs(root).apply { addUser("translator", "secret") }
    private val authenticator = BasicAuthenticator(wacs.apiFactory)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun failedLogin(password: String) = assertFailsWith<WacsAuthException> {
        runBlocking { authenticator.login(wacs.host, "translator", password) }
    }.reason

    @Test
    fun `the right password gives a credential`() {
        val credential = runBlocking { authenticator.login(wacs.host, "translator", "secret") }

        assertEquals(WacsCredential.Basic("translator", "secret"), credential)
    }

    @Test
    fun `a wrong password is invalid credentials`() {
        assertEquals(WacsAuthException.Reason.INVALID_CREDENTIALS, failedLogin("wrong"))
    }

    @Test
    fun `a server error is reported as one`() {
        wacs.fail("GET", "/api/v1/user", 500)

        assertEquals(WacsAuthException.Reason.SERVER_ERROR, failedLogin("secret"))
    }

    @Test
    fun `a host that can't be reached is unreachable`() {
        wacs.unreachable = true

        assertEquals(WacsAuthException.Reason.UNREACHABLE, failedLogin("secret"))
    }
}
