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

import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.slf4j.LoggerFactory
import org.bibletranslationtools.otter.common.data.ProgressStatus

class InitializeApp(
    private val initializeVersification: InitializeVersification,
    private val initializeSources: InitializeSources,
    private val initializeLanguages: InitializeLanguages,
    private val initializeUlb: InitializeUlb,
    private val initializeTakeRepository: InitializeTakeRepository,
    private val initializeProjects: InitializeProjects,
    private val initializeTranslations: InitializeTranslations,
    private val directoryProvider: ITempFileProvider,
    private val daoProvider: DaoProvider
) {

    private val logger = LoggerFactory.getLogger(InitializeApp::class.java)

    fun initApp(): Observable<ProgressStatus> {
        val progressObservable = Observable
            .create { progressStatusEmitter ->
                val initializers = listOf(
                    initializeVersification,
                    initializeLanguages,
                    initializeSources,
                    initializeUlb,
                    initializeTakeRepository,
                    initializeProjects,
                    initializeTranslations
                )

                var total = 0.0
                val increment = (1.0).div(initializers.size)
                val appStart = System.currentTimeMillis()
                daoProvider.withBulkLoad {
                    logger.info("INIT withBulkLoad started (${System.currentTimeMillis() - appStart} ms)")
                    initializers.forEach {
                        total += increment
                        progressStatusEmitter.onNext(
                            ProgressStatus(percent = total)
                        )
                        val stepStart = System.currentTimeMillis()
                        it
                            .exec(progressStatusEmitter)
                            .doOnError { e ->
                                logger.error("Error in Initialization", e)
                            }
                            .blockingAwait()
                        logger.info("INIT ${it::class.simpleName} took ${System.currentTimeMillis() - stepStart} ms (total ${System.currentTimeMillis() - appStart} ms)")
                    }
                }
                logger.info("INIT withBulkLoad complete (${System.currentTimeMillis() - appStart} ms total)")
                progressStatusEmitter.onComplete()
            }
            .doOnError { e ->
                logger.error("Error in initApp", e)
            }
            .doFinally {
                directoryProvider.cleanTempDirectory() // clears out temp files after migration & init
            }
            .subscribeOn(Schedulers.io())

        return progressObservable
    }
}