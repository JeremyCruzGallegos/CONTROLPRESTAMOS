package com.example.controlprestamos.data.google

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** A transaction replaces one account/book snapshot; a failed refresh keeps the previous one. */
class SqliteSheetCache(context: Context, subject: String, book: String,
    private val legacy: SheetCache? = null) : SheetCache {
    private val key = remoteCacheKey(subject, book)
    private val helper = store(context)
    override fun read(): JSONObject? {
        val saved = helper.readableDatabase.query("snapshots", arrayOf("payload"), "owner = ?", arrayOf(key), null, null, null)
            .use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
        if (saved != null) return saved
        return legacy?.read()?.also { write(it); legacy.delete() }
    }
    override fun write(value: JSONObject) {
        helper.writableDatabase.insertWithOnConflict("snapshots", null, ContentValues().apply {
            put("owner", key); put("payload", value.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "No se pudo guardar la base local" } }
    }
    override fun delete() { helper.writableDatabase.delete("snapshots", "owner = ?", arrayOf(key)); legacy?.delete() }
    companion object {
        @Volatile private var shared: Store? = null
        private fun store(context: Context): Store = shared ?: synchronized(this) {
            shared ?: Store(context.applicationContext).also { shared = it }
        }
    }
    private class Store(context: Context) : SQLiteOpenHelper(context, "sheets-local.db", null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE snapshots (owner TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
