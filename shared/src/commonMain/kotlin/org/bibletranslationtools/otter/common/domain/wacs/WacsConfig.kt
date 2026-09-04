/*
 * Copyright (C) 2020-2026 Wycliffe Associates
 *
 * This file is part of Orature.
 *
 * Orature is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Orature is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Orature.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bibletranslationtools.otter.common.domain.wacs

import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient

/**
 * Configuration for talking to a WACS-compatible Forgejo instance: the control-plane REST API
 * ([org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi]), git/LFS transport
 * ([org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient],
 * [org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient]).
 *
 * Provided as a Koin single so it is configurable in one place (see the DI module) rather than
 * hardcoded at each call site. [baseUrl] is what the M1 login screen pre-fills as the default
 * host — a user can still point at a different WACS instance by editing that field; the actual
 * REST client for whatever host they type is built per-login via
 * [org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory], not this single fixed
 * config.
 *
 * Defaults match the local prototype used throughout the M0 spikes.
 */
data class WacsConfig(
    /** Must be reachable as `<baseUrl>api/v1/...`; normalized (trailing `/` added) by callers. */
    val baseUrl: String = "http://localhost:3000/",
    /** The org that holds the *official* audio repos users fork from (see the plan, §4). */
    val org: String = "AudioTranslation",
    val defaultBranch: String = IWacsGitClient.DEFAULT_BRANCH,
    val connectTimeoutSeconds: Long = 30,
    val readTimeoutSeconds: Long = 60,
    val writeTimeoutSeconds: Long = 60,
)
