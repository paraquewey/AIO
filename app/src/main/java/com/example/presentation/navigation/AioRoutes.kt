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
    data object Analysis : AioRoute

    @Serializable
    data object Diagnostics : AioRoute

    @Serializable
    data object Settings : AioRoute

    @Serializable
    data object UiCustomization : AioRoute

    @Serializable
    data object NetworkSettings : AioRoute

    @Serializable
    data object SharingBackgroundSettings : AioRoute

    @Serializable
    data object SecuritySettings : AioRoute

    @Serializable
    data object AboutSettings : AioRoute

    @Serializable
    data class ProfileEditor(val profileId: String? = null) : AioRoute
}
