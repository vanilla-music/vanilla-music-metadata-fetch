/*
 * Copyright (C) 2018-2019 Oleg `Kanedias` Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.metadata

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.util.Log
import com.geecko.fpcalc.FpCalc
import com.google.gson.Gson
import com.kanedias.vanilla.metadata.MetadataFetchActivity.Companion.PLUGIN_TAG_EDIT_PKG
import com.kanedias.vanilla.plugins.PluginConstants
import com.kanedias.vanilla.plugins.PluginConstants.*
import java.io.*
import java.net.URL
import java.util.*
import kotlin.math.roundToInt

/**
 * Main worker of Plugin system. Handles all audio-file work, including loading and parsing audio file,
 * writing it through both filesystem and SAF, upgrade of outdated tags.
 *
 * @see PluginConstants
 * @see MetadataFetchActivity
 *
 * @author Oleg Chernovskiy
 */
class PluginMetadataWrapper(private val mLaunchIntent: Intent) {

    companion object {
        private const val ACOUSTID_API_ENDPOINT = "https://api.acoustid.org/v2/lookup"
        private const val COVERART_API_ENDPOINT = "https://coverartarchive.org"
        private const val META_SEARCH_API_KEY = "Xh7wA9LRfS"
        private const val LOG_TAG = "MetadataExtractor"
    }

    private lateinit var mAudioFile: File

    var metadata: SongMetadata? = null
    var cover: ByteArray? = null

    /**
     * Loads file into the plugin.
     * If error happens while loading, shows popup indicating error details.
     */
    fun loadFile(): Boolean {
        // we need only path passed to us
        val fileUri = mLaunchIntent.getParcelableExtra<Uri>(EXTRA_PARAM_URI)
        if (fileUri == null || fileUri.path == null) {
            return false
        }

        mAudioFile = File(fileUri.path)
        if (!mAudioFile.exists() || !mAudioFile.canRead()) {
            return false
        }

        return true
    }

    /**
     * Loads metadata for the requested file.
     * Launch this in IO thread, not in UI thread.
     */
    fun loadMetadata() {
        val fgJson = FpCalc.fpCalc(arrayOf("-json", mAudioFile.absolutePath))
        val fgData = Gson().fromJson(fgJson, FpCalcFingerprint::class.java)

        val mdAddress = Uri.parse(ACOUSTID_API_ENDPOINT).buildUpon()
                .encodedQuery("meta=recordings+releasegroups+releases+tracks")
                .appendQueryParameter("format", "json")
                .appendQueryParameter("client", META_SEARCH_API_KEY)
                .appendQueryParameter("duration", fgData.duration.toFloat().roundToInt().toString())
                .appendQueryParameter("fingerprint", fgData.fingerprint)
                .build()

        try {
            URL(mdAddress.toString()).openStream().reader().use {
                metadata = Gson().fromJson<SongMetadata>(it.readText(), SongMetadata::class.java)
            }
        } catch (ex: IOException) {
            Log.e(LOG_TAG, "Couldn't load metadata for the file $mAudioFile")
        }
    }

    /**
     * Loads cover for list of releases. First release with cover wins.
     * Launch this in IO thread, not in UI thread.
     */
    fun loadCover() {
        val releaseGroups = metadata?.results
                ?.flatMap { it.recordings.orEmpty() }
                ?.flatMap { it.releaseGroups.orEmpty() } ?: return

        for (rg in releaseGroups.shuffled()) {
            val coverAddress = Uri.parse(COVERART_API_ENDPOINT).buildUpon()
                    .appendPath("release-group")
                    .appendPath(rg.id)
                    .appendPath("front-500")

            try {
                URL(coverAddress.toString()).openStream().use {
                    cover = it.readBytes()
                }
                return
            } catch (ex: IOException) {
                // swallow exception, there are quite a lot of missed covers
                Log.d(LOG_TAG, "Couldn't load cover for release group ${rg.title} (${rg.id})")
                continue
            }
        }

        // didn't find cover by release group, try release instead
        val releases = metadata?.results
                ?.flatMap { it.recordings.orEmpty() }
                ?.flatMap { it.releaseGroups.orEmpty() }
                ?.flatMap { it.releases.orEmpty() } ?: return

        for (release in releases.shuffled()) {
            val coverAddress = Uri.parse(COVERART_API_ENDPOINT).buildUpon()
                    .appendPath("release")
                    .appendPath(release.id)
                    .appendPath("front-500")

            try {
                URL(coverAddress.toString()).openStream().use {
                    cover = it.readBytes()
                }
                return
            } catch (ex: IOException) {
                // swallow exception, there are quite a lot of missed covers
                Log.d(LOG_TAG, "Couldn't load cover for release ${release.title} (${release.id})")
                continue
            }
        }
    }

    fun writeFile(ctx: Activity): Boolean {
        // we are writing through Tag Editor plugin
        writeMeta(ctx)
        writeCover(ctx)
        return true
    }

    /**
     * Write cover to the file through the Tag Editor plugin
     * @param ctx activity to pass intent through
     */
    private fun writeCover(ctx: Activity) {
        if (cover == null)
            return

        // image must be present because this button enables only after it's downloaded
        try {
            val coversDir = File(ctx.cacheDir, "covers")
            if (!coversDir.exists() && !coversDir.mkdir()) {
                Log.e(LOG_TAG, "Couldn't create dir for covers! Path ${ctx.cacheDir}")
                return
            }

            // cleanup old images
            for (oldImg in coversDir.listFiles()) {
                if (!oldImg.delete()) {
                    Log.w(LOG_TAG, "Couldn't delete old image file! Path $oldImg")
                }
            }

            // write artwork to file
            val coverTmpFile = File(coversDir, UUID.randomUUID().toString())
            coverTmpFile.writeBytes(cover!!)

            // create sharable uri
            val uri = FileProvider.getUriForFile(ctx, "com.kanedias.vanilla.metadata.fileprovider", coverTmpFile)

            // send artwork to the tag editor
            val request = Intent(ACTION_LAUNCH_PLUGIN)
            request.setPackage(PLUGIN_TAG_EDIT_PKG)
            request.putExtra(EXTRA_PARAM_URI, ctx.intent.getParcelableExtra(EXTRA_PARAM_URI) as Uri)
            request.putExtra(EXTRA_PARAM_PLUGIN_APP, ctx.applicationInfo)
            request.putExtra(EXTRA_PARAM_P2P, P2P_WRITE_ART)
            if (uri != null) { // artwork write succeeded
                ctx.grantUriPermission(PLUGIN_TAG_EDIT_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                request.putExtra(EXTRA_PARAM_P2P_VAL, uri)
            }
            ctx.startActivity(request)
        } catch (e: IOException) {
            Log.e(LOG_TAG, "Couldn't share private cover image file to tag editor!", e)
        }
    }

    /**
     * Write metadata through the Tag Editor plugin
     * @param ctx activity to pass intent through
     */
    private fun writeMeta(ctx: Activity) {
        val result = metadata?.results?.first()?.recordings?.first() ?: return

        val rgArtists = result.releaseGroups?.flatMap { it.artists.orEmpty() }
        val releases = result.releaseGroups?.flatMap { it.releases.orEmpty() }

        // all release artists, events, track artists and tracks among them
        // metadata is already filtered on fetch to show only related tracks/artists and so on
        val releaseArtists = releases?.flatMap { it.artists }
        val releaseEvents = releases?.flatMap { it.releaseEvents.orEmpty() }
        val trackArtists = releases?.flatMap { it.mediums }?.flatMap { it.tracks }?.flatMap { it.artists }
        val tracks = releases?.flatMap { it.mediums }?.flatMap { it.tracks }

        val request = Intent(ACTION_LAUNCH_PLUGIN)
        request.setPackage(PLUGIN_TAG_EDIT_PKG)
        request.putExtra(EXTRA_PARAM_URI, ctx.intent.getParcelableExtra(EXTRA_PARAM_URI) as Uri)
        request.putExtra(EXTRA_PARAM_PLUGIN_APP, ctx.applicationInfo)
        request.putExtra(EXTRA_PARAM_P2P, P2P_WRITE_TAG)
        request.putExtra(EXTRA_PARAM_P2P_KEY, arrayOf(
                "TITLE",
                "ARTIST",
                "ALBUM",
                "ALBUM_ARTIST",
                "YEAR",
                "COUNTRY",
                "TRACK",
                "TRACK_TOTAL"))
        request.putExtra(EXTRA_PARAM_P2P_VAL, arrayOf<String>(
                // track priority: first found track title -> first found recording title
                tracks?.firstOrNull()?.title ?: result.title.orEmpty(),
                // artist priority: first found recording artist
                //      -> first found release group artist
                //          -> first found album artist
                //              -> first found  track artist
                result.artists?.map { it.name }?.firstOrNull()
                        ?: rgArtists?.map { it.name }?.firstOrNull()
                        ?: releaseArtists?.mapNotNull { it.name }?.firstOrNull()
                        ?: trackArtists?.map { it.name }?.firstOrNull().orEmpty(),
                // album priority: release group title -> first found release title
                result.releaseGroups?.mapNotNull { it.title }?.firstOrNull()
                        ?: releases?.mapNotNull { it.title }?.firstOrNull().orEmpty(),
                // album artist priority: first found release artist
                releaseArtists?.mapNotNull { it.name }?.firstOrNull().orEmpty(),
                // year priority: first found release date
                releaseEvents?.mapNotNull { it.date }?.map { it.year }?.firstOrNull()?.toString().orEmpty(),
                // country priority: first found release country
                releases?.map { it.country }?.firstOrNull().orEmpty(),
                // track pos priority: first found track position
                tracks?.firstOrNull()?.position?.toString().orEmpty(),
                // track total priority: first found release track total
                releases?.mapNotNull { it.trackCount }?.firstOrNull()?.toString().orEmpty()))
        ctx.startActivity(request)
    }
}
