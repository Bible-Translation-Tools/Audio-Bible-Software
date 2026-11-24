package org.bibletranslationtools.orature.verify

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.bibletranslationtools.orature.e2e.harness.OratureUiTestHarness
import org.bibletranslationtools.orature.ui.OratureApp

/**
 * The real Orature window, for checking a feature by hand or by an agent, on a copy of a
 * verification state rather than your own projects. The microphone is the verify microphone
 * (a 330 Hz tone; write another pitch to `<data dir>/verify-mic-hz` to record "in a different
 * voice", see the harness's FakeMicrophone) and the speakers are silent but play in real time,
 * so the app behaves the same on any machine.
 *
 * `./gradlew :app-orature:runVerifyApp -PverifyState=narrated-gen1` copies the state to
 * `app-orature/build/verify-runs/<state>-<time>/` and prints that path: dump it while the app
 * is open or after closing it with `./gradlew :app-orature:dumpState -PdataDir=<path>`.
 * `-PdataDir=<path>` instead reopens an earlier run where it was left.
 */
object VerifyApp {
    @JvmStatic
    fun main(args: Array<String>) {
        val arg = args.firstOrNull()?.ifBlank { null } ?: "fresh"
        val dataDir = File(arg).takeIf { it.isDirectory } ?: copyOfState(VerifyState.of(arg))
        println("VERIFY_DATA_DIR=${dataDir.absolutePath}")
        OratureUiTestHarness.start(dataDir = dataDir)
        application {
            Window(
                onCloseRequest = { OratureUiTestHarness.stop(); exitApplication() },
                title = "Orature (verify: ${dataDir.name})",
                state = rememberWindowState(size = DpSize(1280.dp, 800.dp))
            ) {
                OratureApp(startWithSplash = false)
            }
        }
    }

    private fun copyOfState(state: VerifyState): File {
        val seeded = VerifyState.root.resolve(state.slug)
        check(seeded.isDirectory) {
            "State ${state.slug} is not built yet: ./gradlew :app-orature:seedVerifyStates -PverifyState=${state.slug}"
        }
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val run = File("build/verify-runs/${state.slug}-$time").absoluteFile
        seeded.copyRecursively(run)
        return run
    }
}
