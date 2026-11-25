package com.nextlayer.betweenly.music

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * App-private database for playlists, likes, the queue, and offline index.
 * Opened on the first music screen, or when a shared playlist message arrives.
 * [android.app.Application.onCreate] does not touch it.
 *
 * DATA-FLOW: none. The file never leaves this phone.
 */
internal object MusicDb {
    private val lock = Any()
    private var helper: Helper? = null

    fun <T> use(context: Context, block: (SQLiteDatabase) -> T): T {
        synchronized(lock) {
            val db = open(context)
            return block(db)
        }
    }

    private fun open(context: Context): SQLiteDatabase {
        helper?.let { return it.writableDatabase }
        val created = Helper(context.applicationContext)
        val db = created.writableDatabase
        helper = created
        return db
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE meta (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE likes (" +
                    "source_id TEXT NOT NULL, track_id TEXT NOT NULL, title TEXT NOT NULL, " +
                    "artist TEXT NOT NULL, duration_ms INTEGER NOT NULL, extra TEXT NOT NULL, " +
                    "ts INTEGER NOT NULL, PRIMARY KEY (source_id, track_id))",
            )
            db.execSQL(
                "CREATE TABLE playlists (" +
                    "id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, shared INTEGER NOT NULL, " +
                    "ts INTEGER NOT NULL, deleted INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE playlist_tracks (" +
                    "playlist_id TEXT NOT NULL, source_id TEXT NOT NULL, track_id TEXT NOT NULL, " +
                    "title TEXT NOT NULL, artist TEXT NOT NULL, duration_ms INTEGER NOT NULL, " +
                    "extra TEXT NOT NULL, position INTEGER NOT NULL, ts INTEGER NOT NULL, " +
                    "removed INTEGER NOT NULL, PRIMARY KEY (playlist_id, source_id, track_id))",
            )
            db.execSQL(
                "CREATE TABLE queue (" +
                    "position INTEGER PRIMARY KEY NOT NULL, source_id TEXT NOT NULL, " +
                    "track_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                    "duration_ms INTEGER NOT NULL, extra TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE offline (" +
                    "source_id TEXT NOT NULL, track_id TEXT NOT NULL, title TEXT NOT NULL, " +
                    "artist TEXT NOT NULL, duration_ms INTEGER NOT NULL, extra TEXT NOT NULL, " +
                    "bytes INTEGER NOT NULL, file_name TEXT NOT NULL, " +
                    "PRIMARY KEY (source_id, track_id))",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private const val NAME = "betweenly-music.db"
    private const val VERSION = 1
}
