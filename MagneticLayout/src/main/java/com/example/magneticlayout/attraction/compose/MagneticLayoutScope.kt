package com.example.magneticlayout.attraction.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.absorbable.attraction.compose.attractableInternal
import com.example.magnetic.compose.MagneticController
import com.example.magneticlayout.attraction.controller.AttractionPoint

@Stable
interface MagneticLayoutScope {
    val controller: MagneticController
    fun Modifier.attractable(
        id: String? = null, attractionStrength: Float = 1.0f
    ): Modifier
}

internal class MagneticLayoutScopeImpl(
    override val controller: MagneticController
) : MagneticLayoutScope {
    override fun Modifier.attractable(
        id: String?, attractionStrength: Float
    ): Modifier {
        require(attractionStrength > 0f) {
            "Attraction strength must be positive, was $attractionStrength"
        }
        return this.attractableInternal(
            controller = this@MagneticLayoutScopeImpl.controller,
            id = id,
            attractionStrength = attractionStrength
        )
    }
}

