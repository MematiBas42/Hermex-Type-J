package io.github.jcartu.hermex.ui.feature.models

import android.content.Context
import io.github.jcartu.hermex.HermexApplication
import io.github.jcartu.hermex.model.ModelFavoritesStore
import io.github.jcartu.hermex.model.ModelRecentsStore

/**
 * Process-wide accessors for the single favorites/recents stores owned by
 * [io.github.jcartu.hermex.di.AppContainer]. Chat, settings, and picker sheets
 * therefore observe the same DataStore and iOS-compatible preference keys.
 */
object ModelPickerStores {

    fun favorites(context: Context): ModelFavoritesStore =
        (context.applicationContext as HermexApplication).container.modelFavorites

    fun recents(context: Context): ModelRecentsStore =
        (context.applicationContext as HermexApplication).container.modelRecents
}
