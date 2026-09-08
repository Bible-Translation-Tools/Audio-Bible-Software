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
package org.bibletranslationtools.otter.common.domain.wacs.usecase

import kotlinx.coroutines.CancellationException
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoRepo
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import retrofit2.HttpException
import java.io.IOException

/**
 * M3: page through every repo in the `AudioTranslation` org (see [WacsConfig.org]) for the
 * repo-picker UI — the first step of "pull as source" (the plan doc, §8, M3). No fork/clone here;
 * this is a pure REST list, driving the picker before the user commits to cloning anything.
 */
class ListWacsRepos(
    private val apiFactory: WacsApiFactory,
    private val session: WacsSession,
    private val config: WacsConfig,
) {
    suspend fun list(): List<ForgejoRepo> {
        val credential = session.credential
            ?: throw WacsPullException(WacsPullException.Reason.NOT_AUTHENTICATED)
        val host = session.host ?: config.baseUrl
        val api = apiFactory.create(host)
        val auth = credential.authorizationHeader()

        val all = mutableListOf<ForgejoRepo>()
        var page = 1
        while (true) {
            val batch = try {
                api.listOrgRepos(config.org, auth, page, PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                throw classifyHttp(e)
            } catch (e: IOException) {
                throw WacsPullException(WacsPullException.Reason.NETWORK, e)
            } catch (e: Exception) {
                throw WacsPullException(WacsPullException.Reason.UNKNOWN, e)
            }
            all += batch
            if (batch.size < PAGE_SIZE) break
            page++
        }
        return all
    }

    private fun classifyHttp(e: HttpException): WacsPullException = when {
        e.code() == 401 || e.code() == 403 -> WacsPullException(WacsPullException.Reason.AUTH_REJECTED, e)
        e.code() == 404 -> WacsPullException(WacsPullException.Reason.REPO_NOT_FOUND, e)
        e.code() >= 500 -> WacsPullException(WacsPullException.Reason.NETWORK, e)
        else -> WacsPullException(WacsPullException.Reason.UNKNOWN, e)
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
