package org.bibletranslationtools.otter.common.persistence.repositories

import kotlinx.coroutines.Dispatchers
import org.bibletranslationtools.otter.common.persistence.database.DatabaseWriter
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider

/**
 * The write queue on an immediate dispatcher, so a repository's writes run on the calling thread,
 * as the trampolined `Schedulers.io()` these tests already use makes its reads do.
 */
fun immediateWriter(db: DaoProvider) = DatabaseWriter(db, Dispatchers.Unconfined)
