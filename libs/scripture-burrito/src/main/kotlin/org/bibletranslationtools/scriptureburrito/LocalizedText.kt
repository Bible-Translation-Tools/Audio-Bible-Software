package org.bibletranslationtools.scriptureburrito

/**
 * `common.schema.json#/definitions/localizedText`: one string per IETF language tag, e.g.
 * `{"en": "United States"}`.
 *
 * Distinct from [LocalizedName] (`localized_name.schema.json`), which wraps up to three of THESE
 * under `short`/`abbr`/`long` and requires `short`. The spec uses localizedName only for
 * `localizedNames` (book names); every other localized field — agency names, target areas,
 * promotion statements, template names — is a bare localizedText.
 *
 * The generated model used to type those with the short/abbr/long class, which no conformant file
 * matches. Jackson hid it by passing a null `short` and dropping the real language keys as unknown
 * properties, so agencies and target areas were read as empty and silently discarded; kotlinx
 * enforces the constructor and turned the same bad model into a failed import.
 *
 * A typealias rather than a class because that is already how [IdentificationSchema] and
 * [LanguageSchema] model the same spec type, and it needs no serializer of its own.
 */
typealias LocalizedText = HashMap<String, String>
