# AbsorbableCompose

**A fluid, physics-inspired magnetic attraction system for Jetpack Compose**

*An experimental Android project exploring advanced UI interactions with OpenGL-powered magnetic effects*

---
## Screenshots

https://github.com/user-attachments/assets/af2b2215-2053-467f-9391-413e6fcc58d4


## Project Overview

This project demonstrates a sophisticated magnetic attraction system built for Jetpack Compose. UI elements can be dynamically attracted to predefined points (like the device notch) with fluid animations and advanced visual effects powered by OpenGL ES 3.0.

**Built as a personal exploration of:**
- Advanced Jetpack Compose modifiers and custom layouts
- OpenGL ES integration with Android UI framework
- Physics-inspired animations and interactions
- Complex state management in reactive UI systems

## Features

### Core Functionality
- **Magnetic Attraction System** - Elements automatically attract to defined points when in proximity
- **Fluid Animation Engine** - Custom interpolation with wobble and bounce effects
- **OpenGL Metaball Rendering** - Hardware-accelerated blob-like visual merging
- **Multi-element Stack Management** - LIFO handling of attracted items
- **Distance-based Detection** - Configurable proximity thresholds
- **Reversible Interactions** - Smooth release animations back to original positions

### Technical Implementation
- **Custom Compose Modifiers** - `Modifier.attractable()` for magnetic behavior
- **OpenGL ES 3.0 Shaders** - Fragment shaders for metaball effects
- **Bitmap Capture System** - Seamless visual continuity during animations
- **Thread-safe State Management** - Concurrent handling with proper synchronization
- **Memory Optimization** - Automatic cleanup and resource management

## Project Structure

```
├── app/                                    # Demo application
│   └── src/main/java/com/example/absorbable/
│       └── ProfileScreenDemo.kt            # Demo implementation
└── MagneticLayout/                         # Core library module
    └── src/main/java/com/example/magneticlayout/
        ├── attraction/
        │   ├── compose/
        │   │   ├── Attractable.kt          # Core attractable modifier
        │   │   ├── MagneticLayout.kt       # Main container composable
        │   │   └── MagneticLayoutScope.kt  # Scoped API
        │   ├── controller/
        │   │   ├── AttractionPoint.kt      # Attraction point definitions
        │   │   └── MagneticController.kt   # Central state management
        │   └── utils/
        └── magnetic/
            ├── compose/
            │   └── MagneticController.kt   # Controller factory functions
            └── view/
                ├── MagneticView.kt         # OpenGL surface view
                └── MagneticRenderer.kt     # OpenGL rendering logic
```

## How It Works

### Basic Usage

```kotlin
@Composable
fun MyScreen() {
    val controller = rememberMagneticController()

    MagneticLayout(
        modifier = Modifier.fillMaxSize(),
        controller = controller
    ) {
        // Any composable can become attractable
        Image(
            painter = painterResource(R.drawable.profile),
            modifier = Modifier
                .size(180.dp)
                .attractable("ProfilePic")
        )
        
        FloatingActionButton(
            onClick = { /* action */ },
            modifier = Modifier.attractable("FAB_Edit")
        ) {
            Icon(Icons.Filled.Edit, "Edit")
        }
    }
}
```

### Custom Attraction Points

```kotlin
val controller = rememberMagneticController(
    initialAttractionPoints = listOf(
        AttractionPoint.deviceNotch(context),
        AttractionPoint.center(context, radius = 100f),
        AttractionPoint.topCenter(context, radius = 80f, offsetFromTop = 100f)
    ),
    initialAttractionDistanceThreshold = 200f,
    initialReleaseDistanceThreshold = 250f
)
```

## Key Technical Challenges Solved

### 1. Compose-OpenGL Integration
- Seamless integration of OpenGL rendering within Compose hierarchy
- Proper lifecycle management between Compose and OpenGL contexts
- Thread synchronization between UI and render threads

### 2. Physics-Inspired Animations
- Custom interpolation curves for natural movement
- Wobble effects during attraction phase
- Bounce effects during release phase
- Dynamic size scaling with non-linear curves

### 3. Advanced Visual Effects
```glsl
// Fragment shader excerpt for metaball effects
float metaballField(vec2 p, vec2 center, float radius) {
    float d = length(p - center);
    return radius * radius / (d * d + EPSILON);
}
```
## Architecture

The system consists of several key components:

1. **MagneticController** - Central state management and attraction logic
2. **MagneticLayout** - Compose container that enables magnetic interactions
3. **Attractable Modifier** - Makes any composable element magnetic
4. **OpenGL Renderer** - Handles advanced visual effects and animations
5. **AttractionPoint** - Defines magnetic attraction locations

**Built with:** Kotlin 2.0.21, Jetpack Compose, OpenGL ES 3.0, Coroutines
