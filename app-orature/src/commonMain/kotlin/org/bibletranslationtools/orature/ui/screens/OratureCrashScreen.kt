package org.bibletranslationtools.orature.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bibletranslationtools.orature.crash.OratureCrashInfo
import org.bibletranslationtools.orature.crash.OratureCrashReporter
import org.bibletranslationtools.orature.resources.Res
import org.bibletranslationtools.orature.resources.closeApp
import org.bibletranslationtools.orature.resources.exception_header
import org.bibletranslationtools.orature.resources.needsRestart
import org.bibletranslationtools.orature.resources.sendErrorReport
import org.bibletranslationtools.orature.resources.showLess
import org.bibletranslationtools.orature.resources.showMore
import org.bibletranslationtools.orature.resources.yourWorkSaved
import org.bibletranslationtools.orature.ui.OratureColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen crash overlay (JVM: `ExceptionDialog` / `ExceptionContent` + exception-content.css): a
 * white dialog card on a dim scrim, with the navy `exception_header` banner, the crash title +
 * "your work is saved, restart" message, an expandable (and selectable) stack trace in a
 * primary-light box, a "send error report" checkbox, and a close-app button. Shown by the root
 * composable when [OratureCrashReporter.crash] becomes non-null.
 */
@Composable
fun OratureCrashScreen(info: OratureCrashInfo) {
    val scope = rememberCoroutineScope()
    var showTrace by remember { mutableStateOf(false) }
    // JVM ExceptionContent defaults the "Send Error Report" checkbox to unchecked and always shows it.
    var sendReport by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // Modal scrim: consume every pointer event so the UI behind the crash dialog can't be
            // interacted with (the dialog card is a child, so its buttons/checkbox still work).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 604.dp).fillMaxWidth().padding(24.dp).clip(RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            color = OratureColors.Foreground
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Navy header banner (JVM: exception_header.png).
                Image(
                    painter = painterResource(Res.drawable.exception_header),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp).padding(bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.needsRestart),
                        color = OratureColors.RegularText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(Res.string.yourWorkSaved),
                        color = OratureColors.RegularText,
                        fontSize = 16.sp
                    )

                    // Show more / less toggle (JVM tertiary button with an expand chevron).
                    Row(
                        modifier = Modifier.clickable { showTrace = !showTrace }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (showTrace) stringResource(Res.string.showLess) else stringResource(Res.string.showMore),
                            color = OratureColors.RegularText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = if (showTrace) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = OratureColors.RegularText80
                        )
                    }

                    if (showTrace) {
                        // Primary-light rounded box; the trace text is selectable so it can be copied.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(OratureColors.PrimaryLight)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = info.stackTrace,
                                    color = OratureColors.RegularText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // JVM always shows the "Send Error Report" checkbox.
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = sendReport,
                                onCheckedChange = { sendReport = it },
                                enabled = !sending,
                                colors = CheckboxDefaults.colors(checkedColor = OratureColors.Primary)
                            )
                            Text(
                                stringResource(Res.string.sendErrorReport),
                                color = OratureColors.RegularText,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }

                        if (sending) {
                            CircularProgressIndicator(
                                color = OratureColors.Primary,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (sendReport) {
                                        // Send to every configured uploader off the UI thread, then quit
                                        // (JVM: sendReport().doOnComplete { Platform.exit() }). If nothing
                                        // is configured, sendReport() is a fast no-op.
                                        sending = true
                                        scope.launch {
                                            withContext(Dispatchers.IO) { OratureCrashReporter.sendReport() }
                                            OratureCrashReporter.close()
                                        }
                                    } else {
                                        OratureCrashReporter.close()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = OratureColors.Primary)
                            ) {
                                Text(stringResource(Res.string.closeApp))
                            }
                        }
                    }
                }
            }
        }
    }
}
