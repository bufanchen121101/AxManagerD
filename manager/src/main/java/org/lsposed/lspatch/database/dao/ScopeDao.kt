package org.lsposed.lspatch.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.lsposed.lspatch.database.entity.Module
import org.lsposed.lspatch.database.entity.Scope

@Dao
interface ScopeDao {

    @Query("SELECT * FROM module INNER JOIN scope ON module.pkgName = scope.modulePkgName WHERE scope.appPkgName = :appPkgName")
    suspend fun getModulesForApp(appPkgName: String): List<Module>

    @Query("SELECT appPkgName FROM scope WHERE modulePkgName = :modulePkgName")
    suspend fun getAppsForModule(modulePkgName: String): List<String>

    // IGNORE, not the default ABORT: enabling a module that is already in scope is a no-op the UI
    // may well ask for, and an ABORT throws a constraint exception that takes the rest of a batch
    // down with it.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(scope: Scope)

    @Delete
    suspend fun delete(scope: Scope)

    @Query("DELETE FROM scope WHERE appPkgName = :appPkgName")
    suspend fun deleteForApp(appPkgName: String)
}
