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

import io.reactivex.Completable
import io.reactivex.ObservableEmitter
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.rx2.rxCompletable
import org.bibletranslationtools.otter.common.data.workbook.WorkbookStore
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
import org.bibletranslationtools.otter.common.api.persistence.repositories.ITakeRepository
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus

class InitializeTakeRepository(
    private val takeRepository: ITakeRepository,
    private val workbookStore: WorkbookStore
) : Initializable {

    private val log = LoggerFactory.getLogger(InitializeTakeRepository::class.java)

    override fun exec(progressEmitter: ObservableEmitter<ProgressStatus>): Completable {
        log.info("Initializing take repository...")
        // A rechunk that was never settled (the app closed or was killed while the user was still in
        // the chapter) sticks now: undo history does not survive a restart.
        return rxCompletable { workbookStore.chaptersWithDrafts().forEach { workbookStore.deleteInactiveDrafts(it) } }
            .andThen(takeRepository.removeNonExistentTakes())
            .andThen(takeRepository.deleteExpiredTakes())
            .doOnError { e ->
                log.error("Error initializing take repository", e)
            }
            .doOnComplete {
                log.info("Take repository initialized!")
            }
            .subscribeOn(Schedulers.io())
    }
}
