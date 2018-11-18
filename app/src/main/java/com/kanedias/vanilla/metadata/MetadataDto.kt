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

import com.geecko.fpcalc.FpCalc
import com.google.gson.annotations.SerializedName

/**
 * Output from [FpCalc] in JSON format
 *
 * @author Kanedias
 *
 * Created on 04.11.18
 */
data class FpCalcFingerprint(val duration: String, val fingerprint: String)

/**
 * Song metadata created from JSON answer of AcoustID
 * Example:
 * ```
 * {
 *   "status": "ok",
 *   "results": [{
 *     "score": 1.0,
 *     "id": "9ff43b6a-4f16-427c-93c2-92307ca505e0",
 *     "recordings": [{
 *       "duration": 639,
 *       "releasegroups": [{
 *         "type": "Album",
 *         "id": "ddaa2d4d-314e-3e7c-b1d0-f6d207f5aa2f",
 *         "title": "Before the Dawn Heals Us"
 *       }],
 *       "title": "Lower Your Eyelids to Die With the Sun",
 *       "id": "cd2e7c47-16f5-46c6-a37c-a1eb7bf599ff",
 *       "artists": [{
 *         "id": "6d7b7cd4-254b-4c25-83f6-dd20f98ceacd",
 *         "name": "M83"
 *       }]
 *     }]
 *   }]
 * }
 * ```
 *
 * @author Kanedias
 *
 * Created on 04.11.18
 */
data class SongMetadata(
        val status: String,
        val results: List<Match>
)

data class Match(
        val id: String,
        val score: Float,
        val recordings: List<Recording>
)

data class Recording(
        val id: String,
        val duration: String,
        val title: String?,
        val releases: List<Release>?,
        val artists: List<Artist>?
)

data class Release(
        val id: String,
        val title: String?,
        val country: String?,
        val date: ReleaseDate?,
        val artists: List<Artist>,
        @SerializedName("releaseevents")
        val releaseEvents: List<ReleaseEvent>,
        @SerializedName("track_count")
        val trackCount: Int?,
        @SerializedName("medium_count")
        val mediumCount: Int?,
        val mediums: List<Medium>
)

data class ReleaseEvent(
        val id: String,
        val country: String,
        val date: ReleaseDate?
)

data class ReleaseDate(
        val month: Int,
        val day: Int,
        val year: Int
)

data class Medium(
    @SerializedName("track_count")
    val trackCount: Int?,
    val position: Int?,
    val format: String,
    val tracks: List<Track>
)

data class Track(
        val id: String,
        val title: String,
        val position: Int,
        val artists: List<Artist>
)

data class Artist(
        val id: String,
        val name: String?
)