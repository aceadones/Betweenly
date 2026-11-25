package com.nextlayer.betweenly.music

import android.content.Context

/**
 * Catalogs the music screen can show.
 *
 * [LocalSource] is always included. An online source appears only after
 * [register]. Do not call [register] from [android.app.Application.onCreate]:
 * the process must not build a player, open a socket, or scan the library
 * until the music screen is open. [all] is what the screen calls.
 *
 * DATA-FLOW: none. This list holds instances. It does not search or connect.
 */
object MusicSources {
    private val online = ArrayList<MusicSource>()

    fun all(context: Context): List<MusicSource> {
        val list = ArrayList<MusicSource>()
        list.add(LocalSource(context.applicationContext))
        synchronized(online) { list.addAll(online) }
        return list
    }

    fun find(context: Context, id: String): MusicSource? =
        all(context).firstOrNull { it.id == id }

    /**
     * Adds an online [MusicSource]. Local cannot be replaced.
     * A second register with the same [MusicSource.id] replaces the first.
     * [OnlineSourceStub] is not registered here.
     */
    fun register(source: MusicSource) {
        if (source.id.isBlank() || source.id == LocalSource.ID) return
        synchronized(online) {
            online.removeAll { it.id == source.id }
            online.add(source)
        }
    }
}
