package org.bibletranslationtools.otter.common

import kotlinx.serialization.json.Json

/**
 * The JSON codec for Orature's own on-disk files — chunks.json, checking_status.json,
 * project_mode.json, versification, the language catalogues.
 *
 * Replaces the per-call `ObjectMapper(JsonFactory()).registerKotlinModule()` that each site built
 * for itself, and reproduces the two behaviours those sites relied on:
 *  - `ignoreUnknownKeys` for the class-level @JsonIgnoreProperties(ignoreUnknown = true), since
 *    these files are read across app versions and gain keys over time;
 *  - `explicitNulls = false` for Include.NON_NULL, which several writers set explicitly and which
 *    keeps a null out of the file rather than writing `"key": null`.
 */
val OTTER_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
