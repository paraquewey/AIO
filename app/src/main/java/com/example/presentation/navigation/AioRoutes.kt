package com.example.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface AioRoute {
    @Serializable
    data object Home : AioRoute

    @Serializable
    data object Servers : AioRoute

    @Serializable
    data object Configs : AioRoute

    @Serializable
    data object Diagnostics : AioRoute

    @Serializable
    data object Settings : AioRoute

    @Serializable
    data class ProfileEditor(val profileId: String? = null) : AioRoute
}
