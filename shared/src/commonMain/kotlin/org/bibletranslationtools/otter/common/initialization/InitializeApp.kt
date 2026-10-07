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
import org.bibletranslationtools.otter.common.api.persistence.config.AppStartupStep
import org.bibletranslationtools.otter.common.api.persistence.config.Initializable
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
    private val backfillEditionFingerprints: BackfillEditionFingerprints,
    private val reconcileEditionLabels: ReconcileEditionLabels,
    private val refreshBundledSources: RefreshBundledSources,
    private val auditSourceStructure: AuditSourceStructure,
    private val directoryProvider: ITempFileProvider,
    private val daoProvider: DaoProvider,
    /**
     * The app's own steps, run after the shared ones and outside their bulk load: they may import
     * projects, which want the database in its normal state.
     */
    private val appSteps: List<AppStartupStep> = emptyList()
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
                    initializeTranslations,
                    backfillEditionFingerprints,
                    reconcileEditionLabels,
                    refreshBundledSources,
                    auditSourceStructure
                )

                var total = 0.0
                val increment = (1.0).div(initializers.size + appSteps.size)
                val appStart = System.currentTimeMillis()
                fun run(step: Initializable) {
                    total += increment
                    progressStatusEmitter.onNext(
                        ProgressStatus(percent = total)
                    )
                    val stepStart = System.currentTimeMillis()
                    step
                        .exec(progressStatusEmitter)
                        .doOnError { e ->
                            logger.error("Error in Initialization", e)
                        }
                        .blockingAwait()
                    logger.info("INIT ${step::class.simpleName} took ${System.currentTimeMillis() - stepStart} ms (total ${System.currentTimeMillis() - appStart} ms)")
                }
                daoProvider.withBulkLoad {
                    logger.info("INIT withBulkLoad started (${System.currentTimeMillis() - appStart} ms)")
                    initializers.forEach(::run)
                }
                logger.info("INIT withBulkLoad complete (${System.currentTimeMillis() - appStart} ms total)")
                appSteps.forEach(::run)
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