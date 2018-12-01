/*
 * Copyright (C) 2018 Oleg `Kanedias` Chernovskiy <adonai@xaker.ru>
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

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.*
import com.kanedias.vanilla.plugins.DialogActivity
import com.kanedias.vanilla.plugins.PluginConstants.*
import com.kanedias.vanilla.plugins.PluginUtils
import java.io.IOException
import com.kanedias.vanilla.plugins.PluginUtils.havePermissions
import kotlinx.coroutines.*


/**
 * Main activity of Metatada Fetch plugin. This will be presented as a dialog to the user
 * if one chooses it as the requested plugin.
 *
 * This activity must be able to handle ACTION_WAKE_PLUGIN and ACTION_LAUNCH_PLUGIN
 * intents coming from VanillaMusic. In case these intents are coming from other plugins
 * the activity must try to just silently do required operations without showing actual
 * activity window.
 *
 *
 *
 * Casual conversation looks like this:
 * <pre>
 * VanillaMusic                                 Plugin
 * |                                         |
 * |       ACTION_WAKE_PLUGIN broadcast      |
 * |---------------------------------------->| (plugin init if just installed)
 * |                                         |
 * | ACTION_REQUEST_PLUGIN_PARAMS broadcast  |
 * |---------------------------------------->| (this is handled by BroadcastReceiver)
 * |                                         |
 * |      ACTION_HANDLE_PLUGIN_PARAMS        |
 * |<----------------------------------------| (plugin answer with name and desc)
 * |                                         |
 * |           ACTION_LAUNCH_PLUGIN          |
 * |---------------------------------------->| (plugin is allowed to show window)
</pre> *
 *
 *
 *
 * After metadata fetching is done, this activity can optionally write media info to the file tag itself
 *
 * @author Oleg Chernovskiy
 */
class MetadataFetchActivity : DialogActivity() {

    companion object {
        const val PLUGIN_TAG_EDIT_PKG = "com.kanedias.vanilla.audiotag"

        private const val PROGRESS_VIEW = 0
        private const val METADATA_VIEW = 1
        private const val SAD_CLOUD_VIEW = 2
    }

    private lateinit var title: TextView
    private lateinit var album: TextView
    private lateinit var artist: TextView
    private lateinit var year: TextView
    private lateinit var country: TextView
    private lateinit var cover: ImageView

    private lateinit var pager: ViewFlipper

    private lateinit var mWrite: Button
    private lateinit var mOk: Button

    private lateinit var mWrapper: PluginMetadataWrapper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mWrapper = PluginMetadataWrapper(intent)
        if (handleLaunchPlugin()) {
            // no UI was required for handling the intent
            return
        }

        setContentView(R.layout.activity_metadata_fetch)

        title = findViewById(R.id.meta_song_title)
        album = findViewById(R.id.meta_song_album)
        artist = findViewById(R.id.meta_song_artist)
        year = findViewById(R.id.meta_song_year)
        country = findViewById(R.id.meta_song_country)
        cover = findViewById(R.id.meta_song_cover)

        pager = findViewById(R.id.meta_loading_switcher)

        mWrite = findViewById(R.id.write_button)
        mOk = findViewById(R.id.ok_button)

        setupUI()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = MenuInflater(this)
        inflater.inflate(R.menu.meta_options, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.reload_option -> {
                pager.displayedChild = PROGRESS_VIEW
                if (mWrapper.loadFile()) {
                    extractMetadata()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Checks whether we have selected plugin installed
     * @param pkgName package name of requested plugin to check
     */
    private fun pluginInstalled(pkgName: String): Boolean {
        val resolved = packageManager.queryBroadcastReceivers(Intent(ACTION_REQUEST_PLUGIN_PARAMS), 0)
        for (pkg in resolved) {
            if (TextUtils.equals(pkg.activityInfo.packageName, pkgName)) {
                return true
            }
        }
        return false
    }

    /**
     * Initialize UI elements with handlers and action listeners
     */
    private fun setupUI() {
        if (pluginInstalled(PLUGIN_TAG_EDIT_PKG)) {
            mWrite.visibility = View.VISIBLE
            mWrite.setOnClickListener { mWrapper.writeFile(this) }
        }
        mOk.setOnClickListener { finish() }
    }

    /**
     * Handle Vanilla Music player intents. This will show activity window (in most cases) and load
     * all needed info from file.
     */
    override fun onResume() {
        super.onResume()

        if (!title.text.isNullOrEmpty()) {
            // metadata was already extracted
            return
        }

        // onResume will fire both on first launch and on return from permission request
        if (!PluginUtils.checkAndRequestPermissions(this, READ_EXTERNAL_STORAGE)) {
            return
        }

        if (intent.hasExtra(EXTRA_PARAM_P2P)) {
            // if we're here, then user didn't grant this plugin editor "access to SD" permission before
            // We need to handle this intent again
            handleLaunchPlugin()
            return
        }

        // if we're here the user requested the metadata plugin directly
        if (mWrapper.loadFile()) {
            extractMetadata()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // we only request one permission
        if (!havePermissions(this, READ_EXTERNAL_STORAGE)) {
            // user denied our request, can't continue
            finish()
        }
    }

    /**
     * Retrieves fingerprint from the file and presents it to AcoustID.
     * If match is found, extracts metadata and loads cover from CoverArtArchive.
     */
    private fun extractMetadata() {
        mWrite.isEnabled = false

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // load metadata from AcoustID and cover from CoverArtArchive
                mWrapper.loadMetadata()
                mWrapper.loadCover()

                // show on the screen
                withContext(Dispatchers.Main) { handleCoverartAnswer(mWrapper.cover) }
                withContext(Dispatchers.Main) { handleAcoustidAnswer(mWrapper.metadata) }
            } catch (ex: IOException) {
                Log.e(LOG_TAG, "Exception while loading metadata from AcoustID", ex)
            }
        }
    }

    /**
     * Handle answer of AcoustID. It's JSON filled with match info
     * @param data song metadata retrieved from AcoustID API
     */
    private fun handleAcoustidAnswer(data: SongMetadata?) {
        if (data == null || data.results.isNullOrEmpty() || data.results.first().recordings.isNullOrEmpty()) {
            Toast.makeText(this, R.string.acoustid_no_results, Toast.LENGTH_SHORT).show()
            pager.displayedChild = SAD_CLOUD_VIEW
            return
        }

        if (data.status != "ok") {
            val error = "${getString(R.string.acoustid_status_invalid)}: ${data.status}"
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            pager.displayedChild = SAD_CLOUD_VIEW
            return
        }

        val result = data.results.first().recordings!!.first()
        val releases = result.releaseGroups?.flatMap { it.releases.orEmpty() }

        val allReleaseArtists = releases?.flatMap { it.artists }
        val allTrackArtists = releases?.flatMap { it.mediums }?.flatMap { it.tracks }?.flatMap { it.artists }
        val allTrackTitles = releases?.flatMap { it.mediums }?.flatMap { it.tracks }
        val allReleaseEvents = releases?.flatMap { it.releaseEvents.orEmpty() }

        title.text = result.title
                ?: allTrackTitles?.map { it.title }?.firstOrNull()

        artist.text = result.artists?.map { it.name }?.firstOrNull()
                ?: allReleaseArtists?.map { it.name }?.firstOrNull()
                ?: allTrackArtists?.map { it.name }?.firstOrNull()

        album.text = releases?.map { it.title }?.firstOrNull()

        country.text = releases?.map { it.country }?.firstOrNull()

        year.text = allReleaseEvents?.mapNotNull { it.date }?.map { it.year }?.firstOrNull()?.toString()

        pager.displayedChild = METADATA_VIEW
        mWrite.isEnabled = true
    }


    /**
     * Handle answer from CoverArtArchive. IT's byte array of 500x500 picture of album
     * @param art: artwork in byte array representation
     */
    private fun handleCoverartAnswer(art: ByteArray?) {
        if (art == null)
            return

        val bitmap = BitmapDrawable.createFromStream(art.inputStream(), "cover art") ?: return
        cover.setImageDrawable(bitmap)
    }

    /**
     * Handle incoming intent that may possible be ping, other plugin request or user-interactive plugin request
     * @return true if intent was handled internally, false if activity startup is required
     */
    private fun handleLaunchPlugin(): Boolean {
        if (TextUtils.equals(intent.action, ACTION_WAKE_PLUGIN)) {
            // just show that we're okay
            Log.i(LOG_TAG, "Plugin enabled!")
            finish()
            return true
        }

        // continue startup
        return false
    }
}
