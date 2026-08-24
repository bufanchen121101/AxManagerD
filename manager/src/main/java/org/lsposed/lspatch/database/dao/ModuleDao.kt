package org.lsposed.lspatch.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.lsposed.lspatch.database.entity.Module

@Dao
interface ModuleDao {

    @Query("SELECT * FROM module WHERE pkgName = :pkgName")
    suspend fun getModule(pkgName: String): Module

    @Query("SELECT * FROM module")
    suspend fun getAll(): List<Module>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(module: Module)

    /**
     * Updates a module's apk path in place.
     *
     * An UPDATE, never an `INSERT OR REPLACE`: REPLACE deletes the row and re-inserts it, and the
     * `scope` table cascades on the `module` foreign key -- so a REPLACE here silently wiped every
     * app's enabled-module set on the next app-list refresh. This changes only the path column and
     * touches nothing that depends on the row. A module reinstalled at a new `sourceDir` still needs
     * its recorded path corrected, or the patcher logs "does not exist" and drops it from a patch.
     */
    @Query("UPDATE module SET apkPath = :apkPath WHERE pkgName = :pkgName")
    suspend fun updatePath(pkgName: String, apkPath: String)

    @Delete
    suspend fun delete(module: Module)
}
