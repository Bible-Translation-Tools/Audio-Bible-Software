package org.bibletranslationtools.otter.common.api.persistence.config

/**
 * A startup step an app adds to the shared ones, bound in that app's own Koin module.
 * `InitializeApp` runs every one it is given after its own steps, so `:shared` needn't know them.
 */
interface AppStartupStep : Installable
