package org.bibletranslationtools.shared.di.koin

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.bibletranslationtools.otter.common.domain.wacs.WacsConfig
import org.bibletranslationtools.otter.common.domain.wacs.api.ForgejoApi
import org.bibletranslationtools.otter.common.domain.wacs.api.WacsApiFactory
import org.bibletranslationtools.otter.common.domain.wacs.auth.BasicAuthenticator
import org.bibletranslationtools.otter.common.domain.wacs.auth.IWacsAuthenticator
import org.bibletranslationtools.otter.common.domain.wacs.auth.WacsSession
import org.bibletranslationtools.otter.common.domain.wacs.git.IWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.git.JGitWacsGitClient
import org.bibletranslationtools.otter.common.domain.wacs.lfs.LfsBatchClient
import org.koin.dsl.module

/**
 * WACS sync (M0/M1): the REST control-plane ([ForgejoApi] via [WacsApiFactory]), the hand-rolled
 * LFS transfer client, the JGit-backed git client, and the in-memory auth session/authenticator.
 *
 * [ForgejoApi] itself is bound as a convenience default (built for [WacsConfig.baseUrl]) for use
 * cases that don't need to target an arbitrary host; the login flow instead injects
 * [WacsApiFactory] directly so it can point at whatever host the user types (see
 * [WacsApiFactory]'s KDoc for why one fixed Retrofit instance isn't enough there).
 */
val wacsModule = module {
    single { WacsConfig() }

    // Shared across every WACS REST + LFS-batch call regardless of host: the WA bot-allowlist
    // header (see ForgejoApi.WA_TOOL_HEADER) and consistent timeouts. TimeUnit-based builder calls
    // — this module is pinned to okhttp 3.14.9 (Duration overloads are okhttp 4).
    single {
        val config = get<WacsConfig>()
        OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header(ForgejoApi.WA_TOOL_HEADER, ForgejoApi.WA_TOOL_VALUE)
                        .build()
                    chain.proceed(request)
                }
            )
            .build()
    }

    single { WacsApiFactory(get()) }
    single<ForgejoApi> { get<WacsApiFactory>().create(get<WacsConfig>().baseUrl) }

    single { LfsBatchClient(get()) }
    single<IWacsGitClient> { JGitWacsGitClient() }

    // In-memory only — never persisted (see WacsSession's KDoc).
    single { WacsSession() }
    single<IWacsAuthenticator> { BasicAuthenticator(get()) }
}
