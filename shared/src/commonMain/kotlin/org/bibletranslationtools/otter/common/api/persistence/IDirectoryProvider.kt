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
package org.bibletranslationtools.otter.common.api.persistence

/**
 * Everything the platform knows about where files go, in one object.
 *
 * This used to declare all 27 members itself, which meant a use case that only needed
 * `createTempFile` still compiled against the database directory, the audio plugin directory
 * and the resource container layout. It is now purely the composite of the five ports below,
 * and exists for the two kinds of code that legitimately want the whole thing:
 *
 *  - the platform implementations (`DesktopDirectoryProvider`, `AndroidDirectoryProvider`),
 *    which supply all of it from one notion of "where this installation lives", and
 *  - the DI modules and pass-through factories, which hand the object on without calling it.
 *
 * **Do not inject this into a use case, repository or ViewModel.** Depend on the narrowest of
 * [IAppDirectories], [ITempFileProvider], [IProjectDirectories],
 * [IResourceContainerDirectories] or [IFileIOFactory] that the code actually calls; Koin binds
 * each of them to this same instance. `DirectoryProviderPortsTest` holds the line on the
 * member count so the composite cannot quietly grow members of its own again.
 */
interface IDirectoryProvider :
    IAppDirectories,
    ITempFileProvider,
    IProjectDirectories,
    IResourceContainerDirectories,
    IFileIOFactory
