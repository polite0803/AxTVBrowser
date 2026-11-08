package com.axtv.browser.singleton

import androidx.room.Room
import androidx.room.migration.Migration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The migrations of [AppDatabase] and the schemas the build exports to `app/schemas` go together: every version
 * of the database a device may have must have a step up to the version the app builds, and the migration test
 * needs the schema file of each of those versions to start from. A bump of the version without its migration is
 * a crash on the device of a user who had the older database, so it fails here first.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationChainTest {
    private val schemas = File("schemas/${AppDatabase::class.java.name}")
    private val migrations get() = AppDatabase.MIGRATIONS.asList()

    /** The versions whose schema the build exported */
    private fun exported(): List<Int> {
        val files = schemas.listFiles { file -> file.name.endsWith(".json") }
        assertTrue("No schema exported in ${schemas.path}", files != null && files.isNotEmpty())
        return files!!.map { it.name.removeSuffix(".json").toInt() }.sorted()
    }

    /** The version of the database the app builds, from Room's own definition */
    private fun currentVersion(): Int {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            return db.openHelper.writableDatabase.version
        } finally {
            db.close()
        }
    }

    private fun step(from: Int, to: Int): Migration? =
        migrations.firstOrNull { it.startVersion == from && it.endVersion == to }

    @Test
    fun everyVersionOfAnExportedSchemaHasAStepOn() {
        val current = currentVersion()
        for (version in exported().first() until current) {
            assertTrue("Nothing migrates the database $version → ${version + 1}", step(version, version + 1) != null)
        }
    }

    @Test
    fun eachVersionBelowTheCurrentOneHasItsSchemaExported() {
        val exported = exported()
        val current = currentVersion()
        //Room writes the schema of the version it builds, so the newest file is that version or the one before it
        val below = (exported.first() until current).toSet()
        assertTrue("${schemas.path} holds $exported, expected $below (or with $current, once built)",
            exported.toSet() == below || exported.toSet() == below + current)
    }

    @Test
    fun noMigrationGoesBeyondTheDatabaseOrBackwards() {
        val current = currentVersion()
        for (migration in migrations) {
            assertTrue("${migration.startVersion} → ${migration.endVersion} is no step forward",
                migration.startVersion < migration.endVersion)
            assertTrue("${migration.startVersion} → ${migration.endVersion} is past the version $current of the database",
                migration.endVersion <= current)
        }
    }
}
