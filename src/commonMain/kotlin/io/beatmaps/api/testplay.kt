package io.beatmaps.api

import io.beatmaps.common.api.EMapState
import kotlinx.serialization.Serializable

@Serializable data class MapInfoUpdate(val id: Int, val name: String? = null, val description: String? = null, val deleted: Boolean = false)
@Serializable data class StateUpdate(val hash: String, val state: EMapState, val mapId: Int? = null)
@Serializable data class FeedbackUpdate(val hash: String, val feedback: String, val captcha: String? = null)