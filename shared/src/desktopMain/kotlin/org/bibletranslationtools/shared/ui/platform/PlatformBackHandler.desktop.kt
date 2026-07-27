package org.bibletranslationtools.shared.ui.platform

import androidx.compose.runtime.Composable

// Desktop has no system back affordance to intercept; the on-screen back
// button drives navigation directly.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no-op
}
