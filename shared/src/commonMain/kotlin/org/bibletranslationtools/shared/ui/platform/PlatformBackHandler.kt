package org.bibletranslationtools.shared.ui.platform

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform's system "back" gesture/button while [enabled].
 *
 * Compose Multiplatform doesn't expose a common `BackHandler` in the artifacts
 * this project depends on, so this is an expect/actual shim:
 *   - Android: delegates to `androidx.activity.compose.BackHandler` (hardware /
 *     gesture back, including predictive-back dispatch).
 *   - Desktop: no-op — there is no system back affordance to intercept.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
