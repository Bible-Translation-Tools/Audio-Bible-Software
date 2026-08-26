package org.bibletranslationtools.orature.plugins

import io.mockk.every
import io.mockk.mockk
import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The plugin-selection policy that four Orature ViewModels used to open-code, six copies between
 * them: narration (editor + marker), blind draft (editor + recorder), peer edit, chapter review.
 *
 * Round-trips through the real JSON file so the id/capability matching is tested against what
 * `save` actually wrote, not against a hand-built registry.
 *
 * desktopTest, not commonTest: `selected()` short-circuits to null unless `canLaunchPlugins()` is
 * true, which it only is on desktop (external editors are a desktop-only feature — see
 * PluginLauncher.android.kt, where it is false). Run on Android these assertions fail with
 * `expected:<1> but was:<null>`, because there `selected()` is correctly returning null for a
 * capability the platform cannot provide at all.
 */
class OraturePluginStoreSelectionTest : KoinTest {

    private lateinit var appDataDir: File
    private lateinit var store: OraturePluginStore

    private val editor = OratureExternalPlugin(
        id = 1, name = "Editor", executable = "/bin/edit",
        canEdit = true, canRecord = false, canMark = false
    )
    private val recorder = OratureExternalPlugin(
        id = 2, name = "Recorder", executable = "/bin/rec",
        canEdit = false, canRecord = true, canMark = false
    )
    private val marker = OratureExternalPlugin(
        id = 3, name = "Marker", executable = "/bin/mark",
        canEdit = false, canRecord = false, canMark = true
    )

    @BeforeTest
    fun setup() {
        appDataDir = File.createTempFile("orature-plugins-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        val directories: IAppDirectories = mockk(relaxed = true) {
            every { getAppDataDirectory(any()) } returns appDataDir
        }
        startKoin { modules(module { single { directories } }) }
        store = OraturePluginStore()
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
        appDataDir.deleteRecursively()
    }

    @Test
    fun `picks the plugin selected for each capability`() {
        store.save(
            OraturePluginStore.Registry(
                plugins = listOf(editor, recorder, marker),
                selectedEditorId = editor.id,
                selectedRecorderId = recorder.id,
                selectedMarkerId = marker.id
            )
        )

        assertEquals(editor.id, store.selected(PluginCapability.EDIT)?.id)
        assertEquals(recorder.id, store.selected(PluginCapability.RECORD)?.id)
        assertEquals(marker.id, store.selected(PluginCapability.MARK)?.id)
    }

    /**
     * The case the capability check exists for: a registry can name a plugin for a role that the
     * plugin cannot actually perform — it was registered for that role and later re-registered
     * without the capability, or the id was carried over. Returning it anyway launches a plugin
     * that cannot do the job.
     */
    @Test
    fun `returns null when the selected plugin lacks the capability`() {
        store.save(
            OraturePluginStore.Registry(
                plugins = listOf(editor),
                // Points the recorder and marker roles at an edit-only plugin.
                selectedEditorId = editor.id,
                selectedRecorderId = editor.id,
                selectedMarkerId = editor.id
            )
        )

        assertEquals(editor.id, store.selected(PluginCapability.EDIT)?.id)
        assertNull(store.selected(PluginCapability.RECORD), "editor cannot record")
        assertNull(store.selected(PluginCapability.MARK), "editor cannot mark")
    }

    @Test
    fun `returns null when nothing is selected for the capability`() {
        store.save(OraturePluginStore.Registry(plugins = listOf(editor, recorder, marker)))

        assertNull(store.selected(PluginCapability.EDIT))
        assertNull(store.selected(PluginCapability.RECORD))
        assertNull(store.selected(PluginCapability.MARK))
    }

    @Test
    fun `returns null when the selected id is not in the registry`() {
        store.save(
            OraturePluginStore.Registry(
                plugins = listOf(editor),
                selectedEditorId = 999
            )
        )

        assertNull(store.selected(PluginCapability.EDIT))
    }

    @Test
    fun `an empty registry selects nothing`() {
        assertNull(store.selected(PluginCapability.EDIT))
        assertNull(store.selected(PluginCapability.RECORD))
        assertNull(store.selected(PluginCapability.MARK))
    }

    @Test
    fun `capability maps to the matching plugin flag`() {
        val all = OratureExternalPlugin(
            id = 9, name = "All", executable = "/bin/all",
            canEdit = true, canRecord = true, canMark = true
        )
        assertEquals(true, PluginCapability.EDIT.isSupportedBy(all))
        assertEquals(true, PluginCapability.RECORD.isSupportedBy(all))
        assertEquals(true, PluginCapability.MARK.isSupportedBy(all))

        assertEquals(true, PluginCapability.EDIT.isSupportedBy(editor))
        assertEquals(false, PluginCapability.RECORD.isSupportedBy(editor))
        assertEquals(false, PluginCapability.MARK.isSupportedBy(editor))
    }
}
