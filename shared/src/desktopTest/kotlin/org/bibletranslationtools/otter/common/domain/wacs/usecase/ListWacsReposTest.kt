package org.bibletranslationtools.otter.common.domain.wacs.usecase

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsCredential
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.fake.FakeWacs
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ListWacsReposTest {
    private val root = createTempDirectory("wacs-list").toFile()
    private val wacs = FakeWacs(root)
    private val user = wacs.addUser("translator", "secret")
    private val session = WacsSession().apply { set(user, wacs.host) }
    private val listRepos = ListWacsRepos(wacs.apiFactory, session, WacsConfig(baseUrl = wacs.host))

    init {
        wacs.addOrg("AudioTranslation")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun failure() = assertFailsWith<WacsPullException> { runBlocking { listRepos.list() } }.reason

    @Test
    fun `the user's own repos come first, then the organization's`() {
        wacs.createRepo("AudioTranslation", "audio-en-ulb-mrk")
        wacs.createRepo("AudioTranslation", "audio-en-ulb-tit")
        wacs.createRepo("translator", "audio-en-ulb-mrk")

        val names = runBlocking { listRepos.list() }.map { it.fullName }

        assertEquals(listOf("translator/audio-en-ulb-mrk", "AudioTranslation/audio-en-ulb-mrk", "AudioTranslation/audio-en-ulb-tit"), names)
    }

    @Test
    fun `every page of the organization's repos is listed`() {
        val created = (1..51).map { wacs.createRepo("AudioTranslation", "audio-en-ulb-book$it", files = emptyMap()).fullName }

        val names = runBlocking { listRepos.list() }.map { it.fullName }

        assertEquals(created, names)
    }

    @Test
    fun `listing needs a signed-in user`() {
        session.clear()

        assertEquals(WacsPullException.Reason.NOT_AUTHENTICATED, failure())
    }

    @Test
    fun `a rejected password is reported as such`() {
        session.set(WacsCredential.Basic("translator", "changed"), wacs.host)

        assertEquals(WacsPullException.Reason.AUTH_REJECTED, failure())
    }

    @Test
    fun `a host that can't be reached is a network failure`() {
        wacs.unreachable = true

        assertEquals(WacsPullException.Reason.NETWORK, failure())
    }
}
