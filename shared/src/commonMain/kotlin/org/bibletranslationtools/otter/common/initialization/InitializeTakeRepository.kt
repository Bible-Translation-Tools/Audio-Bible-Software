/**
 * Copyright (C) 2020-2024 Wycliffe Associates
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
package org.bibletranslationtools.otter.common.initialization

import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus

class InitializeTakeRepository(
    private val takeRepository: ITakeRepository
) : Initializable {

    private val log = LoggerFactory.getLogger(InitializeTakeRepository::class.java)

    override suspend fun exec(onProgress: (ProgressStatus) -> Unit) {
        log.info("Initializing take repository...")
        runCatching {
            takeRepository.removeNonExistentTakes()
            takeRepository.deleteExpiredTakesSuspend()
        }
            .onFailure { log.error("Error initializing take repository", it) }
            .getOrThrow()
        log.info("Take repository initialized!")
    }
}
