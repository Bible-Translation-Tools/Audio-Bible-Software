package org.bibletranslationtools.otter.common.data

interface IAppInfo {
    val appName: String
    val appVersion: String
}

data class AppInfo(override val appName: String, override val appVersion: String) : IAppInfo
