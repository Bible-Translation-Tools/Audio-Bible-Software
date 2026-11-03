package org.bibletranslationtools.otter.common.domain.wacs.auth

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BasicAuthenticatorTest {
    private val root = createTempDirectory("wacs-auth").toFile()
    private val wacs = FakeWacs(root).apply { addUser("translator", "secret") }
    private val authenticator = BasicAuthenticator(wacs.apiFactory)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun login(secret: String) = runBlocking { authenticator.login(wacs.host, "translator", secret) }

    private fun failedLogin(secret: String) = assertFailsWith<WacsAuthException> { login(secret) }.reason

    @Test
    fun `the right password gives a token that signs in`() {
        val credential = assertIs<WacsCredential.Token>(login("secret"))

        assertEquals("translator", credential.username)
        val user = runBlocking { wacs.apiFactory.create(wacs.host).whoAmI(credential.authorizationHeader()) }
        assertEquals("translator", user.login)
    }

    @Test
    fun `a token typed as the password is used as the token`() {
        val token = wacs.tokenFor("translator")

        assertEquals(token, login(token.token))
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
