package com.example.magnetic.view

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.IntSize
import androidx.core.animation.addListener
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.example.magnetic.compose.MagneticController
import com.example.magneticlayout.attraction.controller.AttractableItemPosition
import com.example.magneticlayout.attraction.controller.AttractionPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MagneticRenderer(
    private val magneticView: MagneticView, private val controller: MagneticController
) : GLSurfaceView.Renderer {

    private val TAG = "MagneticRenderer"

    // Data class for tracking animating content
    private data class AnimatingContentInfo(
        val id: String,
        val initialPosition: Offset,
        val initialSize: IntSize,
        val targetPosition: Offset,
        val targetSize: IntSize,
        val textureId: Int,
        var currentPosition: Offset = initialPosition,
        var currentSize: IntSize = initialSize,
        var progress: Float = 0f,
        val animator: ValueAnimator
    )

    // Store all animating content
    private val animatingContentMap = ConcurrentHashMap<String, AnimatingContentInfo>()

    // Attraction point state tracking
    private var isPointExpanded = false
    private var pointExpansionAnimator: ValueAnimator? = null
    private var currentPointScale = 1.0f
    private val ATTRACTION_ANIMATION_DURATION_MS = 1200L
    private val RELEASE_ANIMATION_DURATION_MS = 800L
    private val POINT_ANIMATION_DURATION_MS = 500L
    private val EXPANDED_SCALE = 1.6f

    // Shader program
    private var mainProgramId = 0

    // Handles for shader attributes and uniforms
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var resolutionUniform = 0
    private var attractPointPosUniform = 0
    private var attractPointRadiusUniform = 0
    private var attractPointScaleUniform = 0
    private var attractPointColorUniform = 0
    private var contentPosUniform = 0
    private var contentSizeUniform = 0
    private var contentTextureUniform = 0
    private var animProgressUniform = 0
    private var timeUniform = 0
    private var hasContentUniform = 0
    private var isPreviewUniform = 0

    // View dimensions and attraction point properties
    private var viewWidth = 0
    private var viewHeight = 0
    private var primaryPointCenterX: Float = -1f
    private var primaryPointCenterY: Float = -1f
    private var primaryPointRadius: Float = 0f

    // List of all attraction points
    private var attractionPoints = listOf<AttractionPoint>()

    // Release preview variables
    private var showingReleasePreview = false
    private var releasePreviewPosition = Offset.Zero
    private var releasePreviewProgress = 0f

    // Animation time tracking
    private var startTime = System.currentTimeMillis()

    // Buffers for rendering
    private var vertexBuffer: FloatBuffer
    private var indexBuffer: ShortBuffer
    private var texCoordBuffer: FloatBuffer

    // Shader code for metaball effects
    private val vertexShaderCode = """
        #version 300 es
        precision mediump float;
        
        in vec3 aPosition;
        in vec2 aTexCoord;
        
        out vec2 vTexCoord;
        
        void main() {
            gl_Position = vec4(aPosition, 1.0);
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision highp float;
        
        in vec2 vTexCoord;
        out vec4 fragColor;
        
        // Uniforms
        uniform float uTime;
        uniform float uAnimProgress;
        uniform vec2 uResolution;
        uniform vec2 uContentPos;
        uniform vec2 uContentSize;
        uniform vec2 uAttractPointPos;
        uniform float uAttractPointRadius;
        uniform float uAttractPointScale;
        uniform vec4 uAttractPointColor;
        uniform sampler2D uContentTexture;
        uniform int uHasContent;
        uniform int uIsPreview;
        
        #define EPSILON 0.001
        
        // Metaball field function
        float metaballField(vec2 p, vec2 center, float radius) {
            float d = length(p - center);
            return radius * radius / (d * d + EPSILON);
        }
        
        void main() {
            // Normalized device coordinates
            vec2 p = vTexCoord * 2.0 - 1.0;
            float aspect = uResolution.x / uResolution.y;
            p.x *= aspect;
            
            // Setup attraction point (normalize coordinates)
            vec2 pointPos = uAttractPointPos;
            pointPos.x = (pointPos.x / uResolution.x) * 2.0 - 1.0;
            pointPos.y = 1.0 - (pointPos.y / uResolution.y) * 2.0; // Flip Y
            pointPos.x *= aspect;
            
            float pointRadius = (uAttractPointRadius / min(uResolution.x, uResolution.y)) * uAttractPointScale * 0.7;
            
            // Setup content (normalize coordinates)
            vec2 contentPos = uContentPos;
            contentPos.x = (contentPos.x / uResolution.x) * 2.0 - 1.0;
            contentPos.y = 1.0 - (contentPos.y / uResolution.y) * 2.0; // Flip Y
            contentPos.x *= aspect;
            
            vec2 contentSize = uContentSize;
            contentSize.x = (contentSize.x / uResolution.x) * 2.0;
            contentSize.y = (contentSize.y / uResolution.y) * 2.0;
            contentSize.x *= aspect;
            
            float contentRadius = min(contentSize.x, contentSize.y) * 0.5;
            
            // Animated content position
            float progress = clamp(uAnimProgress, 0.0, 1.0);
            
            // Add some wobble to the path during animation for more fluid feel
            float wobble = 0.0;
            if (progress > 0.1 && progress < 0.9) {
                wobble = sin(progress * 12.0) * 0.03 * (1.0 - progress);
            }
            
            // Direction vector for wobble
            vec2 dir = normalize(pointPos - contentPos);
            vec2 perpDir = vec2(-dir.y, dir.x); // Perpendicular to direction
            
            // Apply wobble perpendicular to direction
            vec2 wobbleOffset = perpDir * wobble;
            
            // Animated position with wobble
            vec2 animatedContentPos = mix(contentPos, pointPos, progress) + wobbleOffset;
            
            // Calculate metaball fields
            float pointField = metaballField(p, pointPos, pointRadius);
            float contentField = 0.0;
            
            if (uHasContent == 1) {
                contentField = metaballField(p, animatedContentPos, contentRadius);
            }
            
            // Combined field with smoother blending
            float combinedField = pointField + contentField;
            
            // Threshold for metaball effect
            float threshold = 0.8;
            
            // Sample content texture
            vec4 contentColor = vec4(0.0);
            if (uHasContent == 1) {
                // Calculate texture coordinates in content space
                vec2 localP = p - animatedContentPos;
                // Scale by content size
                vec2 texCoord = (localP / contentSize) + 0.5;
                texCoord.y = 1.0 - texCoord.y; // Flip Y
                
                // Only sample if within content bounds
                if (texCoord.x >= 0.0 && texCoord.x <= 1.0 && 
                    texCoord.y >= 0.0 && texCoord.y <= 1.0) {
                    contentColor = texture(uContentTexture, texCoord);
                    
                    // Apply some distortion to the texture during animation
                    if (progress > 0.0 && progress < 1.0) {
                        // Add stretch/squeeze effect
                        float distort = sin(progress * 3.14159) * 0.2;
                        float distortAmount = distort * (1.0 - length(texCoord - 0.5) * 2.0);
                        
                        // Radial distortion
                        vec2 distortedCoord = texCoord;
                        vec2 toCenter = vec2(0.5) - texCoord;
                        distortedCoord += toCenter * distortAmount;
                        
                        // Ensure we stay in bounds
                        if (distortedCoord.x >= 0.0 && distortedCoord.x <= 1.0 && 
                            distortedCoord.y >= 0.0 && distortedCoord.y <= 1.0) {
                            contentColor = mix(contentColor, 
                                              texture(uContentTexture, distortedCoord),
                                              min(progress * 2.0, 0.5));
                        }
                    }
                }
            }
            
            // Attraction point color
            vec4 pointColor = uAttractPointColor;
            
            // Preview mode has special rendering
            if (uIsPreview == 1) {
                // Make preview more transparent
                pointColor.a *= 0.7;
                
                // Add pulsing effect
                float pulse = (sin(uTime * 5.0) + 1.0) * 0.1;
                pointRadius *= (1.0 + pulse);
            }
            
            // Final color calculation
            vec4 finalColor = vec4(0.0);
            
            // If inside the metaball field
            if (combinedField > threshold) {
                // Determine which field is stronger at this point
                if (pointField > contentField * 1.2) {
                    finalColor = pointColor;
                } else if (contentColor.a > 0.0) {
                    // Blend content with point color near the edges for smoother transition
                    float blendFactor = max(0.0, 1.0 - (contentField / pointField) * 0.8);
                    finalColor = mix(contentColor, pointColor, blendFactor * progress);
                } else {
                    finalColor = pointColor;
                }
                
                // Add a slight glow effect at the edges
                float edge = smoothstep(threshold, threshold + 0.3, combinedField) * 
                             (1.0 - smoothstep(threshold + 0.3, threshold + 0.8, combinedField));
                
                finalColor.rgb += edge * 0.3; // Edge highlight
                finalColor.rgb = min(finalColor.rgb, vec3(1.0)); // Clamp
            }
            
            // Set alpha for the metaball (fully opaque inside the field)
            float fieldAlpha = smoothstep(threshold - 0.05, threshold + 0.05, combinedField);
            finalColor.a *= fieldAlpha;
            
            // Add subtle time-based animation to the attraction point
            if (uIsPreview != 1) {
                // Animate point with subtle pulsing
                float breathe = sin(uTime * 2.0) * 0.03;
                
                // Only apply to the edges
                if (pointField > 0.5 * threshold && pointField < 1.5 * threshold) {
                    finalColor.rgb *= (1.0 + breathe);
                    finalColor.a *= (1.0 - breathe * 0.5);
                }
            }
            
            // Output the final color
            fragColor = finalColor;
        }
    """.trimIndent()

    init {
        // Initialize vertex buffer
        val vertexData = floatArrayOf(
            // X, Y, Z
            -1.0f, -1.0f, 0.0f,  // Bottom left
            1.0f, -1.0f, 0.0f,   // Bottom right
            -1.0f, 1.0f, 0.0f,   // Top left
            1.0f, 1.0f, 0.0f     // Top right
        )

        vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertexData)
                position(0)
            }

        // Create index buffer for drawing order
        val indexData = shortArrayOf(
            0, 1, 2,  // First triangle
            1, 3, 2   // Second triangle
        )

        indexBuffer = ByteBuffer.allocateDirect(indexData.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(indexData)
                position(0)
            }

        // Initialize texture coordinates
        val texCoordData = floatArrayOf(
            0.0f, 0.0f,  // Bottom left
            1.0f, 0.0f,  // Bottom right
            0.0f, 1.0f,  // Top left
            1.0f, 1.0f   // Top right
        )

        texCoordBuffer = ByteBuffer.allocateDirect(texCoordData.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(texCoordData)
                position(0)
            }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")

        // Set clear color (transparent)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)

        // Enable blending for transparency
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        // Create and link the shader program
        createShaderProgram()

        // Reset animation timer
        startTime = System.currentTimeMillis()

        // Initialize attraction point scale
        currentPointScale = if (controller.getStackSize() > 0) EXPANDED_SCALE else 1.0f
        isPointExpanded = controller.getStackSize() > 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: $width x $height")

        // Update viewport
        GLES30.glViewport(0, 0, width, height)

        // Store dimensions
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // Clear the framebuffer
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Skip rendering if program not initialized
        if (mainProgramId == 0) {
            return
        }

        // Calculate elapsed time for animations
        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000f

        // Use shader program
        GLES30.glUseProgram(mainProgramId)

        // Set common uniforms
        GLES30.glUniform2f(resolutionUniform, viewWidth.toFloat(), viewHeight.toFloat())
        GLES30.glUniform1f(timeUniform, elapsedTime)

        // Set up vertex attributes
        setupVertexAttributes()

        // Draw the primary attraction point
        drawAttractionPoint(elapsedTime)

        // Draw all animating content
        drawAnimatingContent(elapsedTime)

        // Draw release preview if active
        if (showingReleasePreview) {
            drawReleasePreview(elapsedTime)
        }

        // Clean up
        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun setupVertexAttributes() {
        // Enable and set the position attribute
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glVertexAttribPointer(
            positionHandle, 3,                   // 3 components per vertex (x, y, z)
            GLES30.GL_FLOAT,     // Type
            false,              // Not normalized
            0,                   // Stride (tightly packed)
            vertexBuffer         // Buffer
        )

        // Enable and set the texture coordinate attribute
        GLES30.glEnableVertexAttribArray(texCoordHandle)
        GLES30.glVertexAttribPointer(
            texCoordHandle, 2,                   // 2 components per vertex (s, t)
            GLES30.GL_FLOAT,     // Type
            false,              // Not normalized
            0,                   // Stride (tightly packed)
            texCoordBuffer       // Buffer
        )
    }

    private fun createShaderProgram() {
        Log.d(TAG, "Creating shader program")

        // Compile shaders
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "Failed to compile shaders")
            return
        }

        // Create the program
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "Failed to create program")
            return
        }

        // Attach shaders to the program
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)

        // Link the program
        GLES30.glLinkProgram(program)

        // Check for linking success
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Error linking program: " + GLES30.glGetProgramInfoLog(program))
            GLES30.glDeleteProgram(program)
            return
        }

        // Store program ID
        mainProgramId = program

        // Get attribute locations
        positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES30.glGetAttribLocation(program, "aTexCoord")

        // Get uniform locations
        resolutionUniform = GLES30.glGetUniformLocation(program, "uResolution")
        timeUniform = GLES30.glGetUniformLocation(program, "uTime")

        attractPointPosUniform = GLES30.glGetUniformLocation(program, "uAttractPointPos")
        attractPointRadiusUniform = GLES30.glGetUniformLocation(program, "uAttractPointRadius")
        attractPointScaleUniform = GLES30.glGetUniformLocation(program, "uAttractPointScale")
        attractPointColorUniform = GLES30.glGetUniformLocation(program, "uAttractPointColor")

        contentPosUniform = GLES30.glGetUniformLocation(program, "uContentPos")
        contentSizeUniform = GLES30.glGetUniformLocation(program, "uContentSize")
        contentTextureUniform = GLES30.glGetUniformLocation(program, "uContentTexture")
        animProgressUniform = GLES30.glGetUniformLocation(program, "uAnimProgress")

        hasContentUniform = GLES30.glGetUniformLocation(program, "uHasContent")
        isPreviewUniform = GLES30.glGetUniformLocation(program, "uIsPreview")

        // Clean up shaders (they're linked to the program now)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)

        Log.d(TAG, "Shader program created successfully")
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        // Create the shader
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            Log.e(TAG, "Failed to create shader of type $type")
            return 0
        }

        // Set the shader source
        GLES30.glShaderSource(shader, shaderCode)

        // Compile the shader
        GLES30.glCompileShader(shader)

        // Check for compilation success
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)

        if (compileStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Error compiling shader of type $type: " + GLES30.glGetShaderInfoLog(shader))
            GLES30.glDeleteShader(shader)
            return 0
        }

        return shader
    }

    private fun drawAttractionPoint(time: Float) {
        if (mainProgramId == 0) return

        // Set attraction point properties
        GLES30.glUniform2f(attractPointPosUniform, primaryPointCenterX, primaryPointCenterY)
        GLES30.glUniform1f(attractPointRadiusUniform, primaryPointRadius)
        GLES30.glUniform1f(attractPointScaleUniform, currentPointScale)

        // Set attraction point color (black by default)
        GLES30.glUniform4f(attractPointColorUniform, 0.0f, 0.0f, 0.0f, 1.0f)

        // Set content flags
        GLES30.glUniform1i(hasContentUniform, 0) // No content for just the point
        GLES30.glUniform1i(isPreviewUniform, 0) // Not a preview

        // No content, so set dummy values for content uniforms
        GLES30.glUniform2f(contentPosUniform, primaryPointCenterX, primaryPointCenterY)
        GLES30.glUniform2f(contentSizeUniform, 0f, 0f)
        GLES30.glUniform1f(animProgressUniform, 0f)

        // Create a dummy texture if needed
        val dummyTexture = createDummyTexture()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dummyTexture)
        GLES30.glUniform1i(contentTextureUniform, 0)

        // Draw the attraction point
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, 6, // 6 indices (2 triangles)
            GLES30.GL_UNSIGNED_SHORT, indexBuffer
        )

        // Clean up
        GLES30.glDeleteTextures(1, intArrayOf(dummyTexture), 0)
    }

    private fun drawAnimatingContent(time: Float) {
        if (mainProgramId == 0 || animatingContentMap.isEmpty()) return

        // Draw each animating item
        for (contentInfo in animatingContentMap.values) {
            // Skip if invalid texture
            if (contentInfo.textureId <= 0) continue

            // Set attraction point properties
            GLES30.glUniform2f(attractPointPosUniform, primaryPointCenterX, primaryPointCenterY)
            GLES30.glUniform1f(attractPointRadiusUniform, primaryPointRadius)
            GLES30.glUniform1f(attractPointScaleUniform, currentPointScale)
            GLES30.glUniform4f(attractPointColorUniform, 0.0f, 0.0f, 0.0f, 1.0f)

            // Set content properties
            GLES30.glUniform2f(
                contentPosUniform, contentInfo.currentPosition.x, contentInfo.currentPosition.y
            )
            GLES30.glUniform2f(
                contentSizeUniform,
                contentInfo.currentSize.width.toFloat(),
                contentInfo.currentSize.height.toFloat()
            )

            // Set animation progress
            GLES30.glUniform1f(animProgressUniform, contentInfo.progress)

            // Set content flags
            GLES30.glUniform1i(hasContentUniform, 1) // Has content
            GLES30.glUniform1i(isPreviewUniform, 0) // Not a preview

            // Bind texture
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, contentInfo.textureId)
            GLES30.glUniform1i(contentTextureUniform, 0)

            // Draw the content
            GLES30.glDrawElements(
                GLES30.GL_TRIANGLES, 6, // 6 indices (2 triangles)
                GLES30.GL_UNSIGNED_SHORT, indexBuffer
            )
        }
    }

    private fun drawReleasePreview(time: Float) {
        if (mainProgramId == 0) return

        // Set attraction point properties
        GLES30.glUniform2f(attractPointPosUniform, primaryPointCenterX, primaryPointCenterY)
        GLES30.glUniform1f(attractPointRadiusUniform, primaryPointRadius)
        GLES30.glUniform1f(attractPointScaleUniform, currentPointScale)

        // Set preview color (more transparent)
        GLES30.glUniform4f(attractPointColorUniform, 0.5f, 0.5f, 0.5f, 0.7f)

        // Set content properties
        GLES30.glUniform2f(contentPosUniform, releasePreviewPosition.x, releasePreviewPosition.y)

        // Size is based on the attraction point
        val previewSize = primaryPointRadius * 2f
        GLES30.glUniform2f(contentSizeUniform, previewSize, previewSize)

        // Set animation progress
        GLES30.glUniform1f(animProgressUniform, releasePreviewProgress)

        // Set content flags
        GLES30.glUniform1i(hasContentUniform, 1) // Has content
        GLES30.glUniform1i(isPreviewUniform, 1) // Is a preview

        // Create a preview texture
        val previewTexture = createPreviewTexture()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previewTexture)
        GLES30.glUniform1i(contentTextureUniform, 0)

        // Draw the preview
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, 6, // 6 indices (2 triangles)
            GLES30.GL_UNSIGNED_SHORT, indexBuffer
        )

        // Clean up
        GLES30.glDeleteTextures(1, intArrayOf(previewTexture), 0)
    }

    private fun createDummyTexture(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        // Bind and set parameters
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
        )

        // Create a small dummy texture
        val size = 2
        val buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until size * size) {
            buffer.put(0.toByte()) // R
            buffer.put(0.toByte()) // G
            buffer.put(0.toByte()) // B
            buffer.put(0.toByte()) // A
        }
        buffer.position(0)

        // Upload to GPU
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            size,
            size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )

        return textureId
    }

    private fun createPreviewTexture(): Int {
        try {
            val size = 64
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)

            // Get a color based on the attraction point
            val color = "#4287f5".toColorInt() // Bright blue

            // Create a radial gradient
            val gradient = RadialGradient(
                size / 2f, size / 2f, size / 2f, intArrayOf(
                    color, Color.argb(100, Color.red(color), Color.green(color), Color.blue(color))
                ), floatArrayOf(0.0f, 1.0f), Shader.TileMode.CLAMP
            )

            val paint = Paint().apply {
                shader = gradient
                isAntiAlias = true
            }

            // Draw a circle
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            // Create texture
            val textureId = createTextureFromBitmap(bitmap)
            bitmap.recycle()

            return textureId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating preview texture", e)
            return createDummyTexture()
        }
    }

    private fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val textureId = textures[0]

        try {
            // Bind texture
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)

            // Set texture parameters
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE
            )

            // Upload bitmap to texture
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

            // Check for errors
            val error = GLES30.glGetError()
            if (error != GLES30.GL_NO_ERROR) {
                Log.e(TAG, "Error loading texture: $error")
                GLES30.glDeleteTextures(1, textures, 0)
                return 0
            }

            return textureId
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating texture from bitmap", e)
            GLES30.glDeleteTextures(1, textures, 0)
            return 0
        }
    }

    private fun animatePointSize(targetScale: Float) {
        // Running on the main thread to avoid Looper errors
        Handler(Looper.getMainLooper()).post {
            // Cancel any running animation
            pointExpansionAnimator?.cancel()

            // Create new animator
            pointExpansionAnimator = ValueAnimator.ofFloat(currentPointScale, targetScale).apply {
                duration = POINT_ANIMATION_DURATION_MS

                // Use overshoot for growing, ease-out for shrinking
                interpolator = if (targetScale > currentPointScale) {
                    OvershootInterpolator(1.5f)
                } else {
                    PathInterpolator(0.0f, 0.0f, 0.2f, 1.0f)
                }

                addUpdateListener { animation ->
                    currentPointScale = animation.animatedValue as Float
                    magneticView.requestRender()
                }

                addListener(
                    onEnd = {
                        currentPointScale = targetScale
                        isPointExpanded = targetScale > 1.0f
                        magneticView.requestRender()
                    })

                start()
            }
        }
    }

    // Public API
    /**
     * Set primary attraction point properties
     */
    fun setPrimaryAttractionPoint(cx: Float, cy: Float, r: Float) {
        if (cx != primaryPointCenterX || cy != primaryPointCenterY || r != primaryPointRadius) {
            primaryPointCenterX = cx
            primaryPointCenterY = cy
            primaryPointRadius = r
            magneticView.requestRender()
        }
    }

    /**
     * Set all attraction points
     */
    fun setAttractionPoints(points: List<AttractionPoint>) {
        attractionPoints = points
        magneticView.requestRender()
    }

    /**
     * Start animation for attracting items
     */
    fun startAttractionAnimation(
        id: String,
        itemPosition: AttractableItemPosition,
        targetPosition: Offset,
        bitmap: Bitmap?,
    ) {
        Log.d(TAG, "Starting attraction animation for $id")

        // Basic error checking
        if (bitmap == null || bitmap.isRecycled || viewWidth <= 0 || viewHeight <= 0) {
            Handler(Looper.getMainLooper()).post {
                magneticView.onAttractionAnimationCompleted(id)
            }
            return
        }

        // If this is the first item, expand the point
        val wasEmpty = controller.getStackSize() <= 1
        if (wasEmpty && !isPointExpanded) {
            animatePointSize(EXPANDED_SCALE)
        }

        val capturedId = id

        // Create a safe copy of the bitmap to avoid recycling issues
        val capturedBitmap = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying bitmap: ${e.message}")
            Handler(Looper.getMainLooper()).post {
                magneticView.onAttractionAnimationCompleted(id)
            }
            return
        }

        val capturedItemPosition = itemPosition
        val capturedTargetPosition = targetPosition

        // Original size
        val initialSize = IntSize(
            itemPosition.size.width.toInt(), itemPosition.size.height.toInt()
        )

        // Final size (tiny)
        val targetSize = IntSize(4, 4)

        magneticView.queueEvent {
            // Create texture from bitmap
            val textureId = createTextureFromBitmap(capturedBitmap)
            capturedBitmap.recycle() // Recycle the copy

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                if (textureId <= 0) {
                    Log.e(TAG, "Failed to create texture for $capturedId")
                    magneticView.onAttractionAnimationCompleted(capturedId)
                    return@post
                }

                Log.d(TAG, "Starting fluid animation for $capturedId")

                // Cancel any existing animation
                animatingContentMap[capturedId]?.animator?.cancel()

                // Create fluid path interpolator
                val fluidInterpolator = PathInterpolator(0.2f, 0.0f, 0.4f, 1.0f)

                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ATTRACTION_ANIMATION_DURATION_MS
                    interpolator = fluidInterpolator

                    addUpdateListener { animation ->
                        val progress = animation.animatedValue as Float

                        animatingContentMap[capturedId]?.let { info ->
                            // Calculate current position with wobble effect
                            val wobble = if (progress > 0.1f && progress < 0.9f) {
                                sin(progress * 12f) * 0.05f * (1.0f - progress)
                            } else 0f

                            // Direction vector
                            val dir = info.targetPosition - info.initialPosition
                            val dirLength = sqrt(dir.x * dir.x + dir.y * dir.y)

                            // Safe normalization
                            val normalizedDir = if (dirLength > 0.001f) {
                                Offset(dir.x / dirLength, dir.y / dirLength)
                            } else {
                                Offset(0f, 1f)
                            }

                            // Perpendicular direction for wobble
                            val perpDir = Offset(-normalizedDir.y, normalizedDir.x)

                            // Apply wobble perpendicular to direction
                            val wobbleOffset = Offset(
                                perpDir.x * wobble * dirLength, perpDir.y * wobble * dirLength
                            )

                            // Update position with wobble
                            val lerpPosition = lerp(
                                info.initialPosition, info.targetPosition, progress
                            )

                            info.currentPosition = Offset(
                                lerpPosition.x + wobbleOffset.x, lerpPosition.y + wobbleOffset.y
                            )

                            // Update size with non-linear curve
                            val sizeFactor = cos((1.0f - progress) * (PI / 2).toFloat())
                            val width = androidx.compose.ui.util.lerp(
                                info.initialSize.width.toFloat(),
                                info.targetSize.width.toFloat(),
                                sizeFactor
                            ).toInt()

                            val height = androidx.compose.ui.util.lerp(
                                info.initialSize.height.toFloat(),
                                info.targetSize.height.toFloat(),
                                sizeFactor
                            ).toInt()

                            info.currentSize = IntSize(
                                max(1, width), max(1, height)
                            )

                            // Update progress
                            info.progress = progress

                            // Request render
                            magneticView.requestRender()
                        }
                    }

                    addListener(onEnd = {
                        Log.d(TAG, "Attraction animation completed for $capturedId")

                        // Safe removal
                        animatingContentMap.remove(capturedId)?.let { finishedInfo ->
                            // Delete texture on GL thread
                            magneticView.queueEvent {
                                GLES30.glDeleteTextures(
                                    1, intArrayOf(finishedInfo.textureId), 0
                                )
                            }

                            // Notify completion
                            magneticView.onAttractionAnimationCompleted(capturedId)
                        }
                    }, onCancel = {
                        Log.d(TAG, "Attraction animation cancelled for $capturedId")

                        // Safe removal
                        animatingContentMap.remove(capturedId)?.let { cancelledInfo ->
                            // Delete texture on GL thread
                            magneticView.queueEvent {
                                GLES30.glDeleteTextures(
                                    1, intArrayOf(cancelledInfo.textureId), 0
                                )
                            }
                        }
                    })
                }

                // Create animation info
                val animInfo = AnimatingContentInfo(
                    id = capturedId,
                    initialPosition = capturedItemPosition.center,
                    initialSize = initialSize,
                    targetPosition = capturedTargetPosition,
                    targetSize = targetSize,
                    textureId = textureId,
                    animator = animator
                )

                // Store and start
                animatingContentMap[capturedId] = animInfo

                try {
                    animator.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting animation", e)

                    // Clean up
                    animatingContentMap.remove(capturedId)
                    magneticView.queueEvent {
                        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                    }

                    // Notify completion
                    magneticView.onAttractionAnimationCompleted(capturedId)
                }
            }
        }
    }

    /**
     * Start animation for releasing items
     */
    fun startReleaseAnimation(
        id: String, startPosition: Offset, targetPosition: Offset, releasedBitmap: Bitmap? = null
    ) {
        Log.d(TAG, "Starting release animation for $id")

        // Cancel any existing animation
        animatingContentMap[id]?.animator?.cancel()

        if (viewWidth <= 0 || viewHeight <= 0) {
            Handler(Looper.getMainLooper()).post {
                magneticView.onReleaseAnimationCompleted(id)
            }
            return
        }

        // Check if this is the last item
        val willBeEmpty = controller.getStackSize() <= 1

        // If this is the last item, shrink the point
        if (willBeEmpty && isPointExpanded) {
            animatePointSize(1.0f)
        }

        val capturedId = id
        val capturedStartPosition = startPosition
        val capturedTargetPosition = targetPosition

        // Calculate initial size (small dot at attraction point)
        val initialDim = (primaryPointRadius * 0.4f).toInt()
        val initialSize = IntSize(max(1, initialDim), max(1, initialDim))

        // Calculate target size
        val targetWidth = 150
        val targetHeight = 150

        magneticView.queueEvent {
            // Create texture on GL thread
            val textureId = if (releasedBitmap != null && !releasedBitmap.isRecycled) {
                createTextureFromBitmap(releasedBitmap)
            } else {
                // Create a fallback colored bitmap
                val fallbackBitmap = createColoredBitmap(capturedId)
                val textureFromFallback = if (fallbackBitmap != null) {
                    createTextureFromBitmap(fallbackBitmap)
                } else 0

                fallbackBitmap?.recycle()
                textureFromFallback
            }

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                if (textureId <= 0) {
                    Log.e(TAG, "Failed to create texture for release animation $capturedId")
                    magneticView.onReleaseAnimationCompleted(capturedId)
                    return@post
                }

                // Fluid release interpolator (fast initial escape, then deceleration)
                val releaseInterpolator = PathInterpolator(0.6f, 0.0f, 0.9f, 1.0f)

                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = RELEASE_ANIMATION_DURATION_MS
                    interpolator = releaseInterpolator

                    addUpdateListener { animation ->
                        val progress = animation.animatedValue as Float

                        animatingContentMap[capturedId]?.let { info ->
                            // Calculate current position
                            val wobble = if (progress > 0.1f && progress < 0.7f) {
                                sin(progress * 8f) * 0.08f * (1.0f - progress)
                            } else 0f

                            // Direction vector
                            val dir = info.targetPosition - info.initialPosition
                            val dirLength = sqrt(dir.x * dir.x + dir.y * dir.y)

                            // Safe normalization
                            val normalizedDir = if (dirLength > 0.001f) {
                                Offset(dir.x / dirLength, dir.y / dirLength)
                            } else {
                                Offset(0f, 1f)
                            }

                            // Perpendicular direction for wobble
                            val perpDir = Offset(-normalizedDir.y, normalizedDir.x)

                            // Apply wobble perpendicular to direction
                            val wobbleOffset = Offset(
                                perpDir.x * wobble * dirLength, perpDir.y * wobble * dirLength
                            )

                            // Update position with wobble
                            val lerpPosition = lerp(
                                info.initialPosition, info.targetPosition, progress
                            )

                            info.currentPosition = Offset(
                                lerpPosition.x + wobbleOffset.x, lerpPosition.y + wobbleOffset.y
                            )

                            // Update size with non-linear curve and bounce
                            val bounce = if (progress > 0.7f) {
                                val bouncePhase = (progress - 0.7f) / 0.3f
                                sin(bouncePhase * PI.toFloat() * 2) * 0.15f * (1.0f - bouncePhase)
                            } else 0f

                            val sizeFactor = (progress.pow(0.6f)) * (1.0f + bounce)

                            val width = androidx.compose.ui.util.lerp(
                                info.initialSize.width.toFloat(),
                                info.targetSize.width.toFloat(),
                                sizeFactor
                            ).toInt()

                            val height = androidx.compose.ui.util.lerp(
                                info.initialSize.height.toFloat(),
                                info.targetSize.height.toFloat(),
                                sizeFactor
                            ).toInt()

                            info.currentSize = IntSize(
                                max(1, width), max(1, height)
                            )

                            // Update progress (inverted for shader)
                            info.progress = 1.0f - progress

                            // Request render
                            magneticView.requestRender()
                        }
                    }

                    addListener(onEnd = {
                        Log.d(TAG, "Release animation completed for $capturedId")

                        // Safe removal
                        animatingContentMap.remove(capturedId)?.let { finishedInfo ->
                            // Delete texture on GL thread
                            magneticView.queueEvent {
                                GLES30.glDeleteTextures(
                                    1, intArrayOf(finishedInfo.textureId), 0
                                )
                            }

                            // Notify completion
                            magneticView.onReleaseAnimationCompleted(capturedId)
                        }
                    }, onCancel = {
                        Log.d(TAG, "Release animation cancelled for $capturedId")

                        // Safe removal
                        animatingContentMap.remove(capturedId)?.let { cancelledInfo ->
                            // Delete texture on GL thread
                            magneticView.queueEvent {
                                GLES30.glDeleteTextures(
                                    1, intArrayOf(cancelledInfo.textureId), 0
                                )
                            }
                        }
                    })
                }

                // Create animation info
                val animInfo = AnimatingContentInfo(
                    id = capturedId,
                    initialPosition = capturedStartPosition,
                    initialSize = initialSize,
                    targetPosition = capturedTargetPosition,
                    targetSize = IntSize(targetWidth, targetHeight),
                    textureId = textureId,
                    progress = 1.0f, // Start as fully attracted
                    animator = animator
                )

                // Store and start
                animatingContentMap[capturedId] = animInfo

                try {
                    animator.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting release animation", e)

                    // Clean up
                    animatingContentMap.remove(capturedId)
                    magneticView.queueEvent {
                        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                    }

                    // Notify completion
                    magneticView.onReleaseAnimationCompleted(capturedId)
                }
            }
        }
    }

    /**
     * Create a colored bitmap for release animation fallback
     */
    private fun createColoredBitmap(id: String): Bitmap? {
        try {
            val size = 128
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)

            // Generate a vibrant color based on the item ID
            val baseColor = when (id.hashCode() % 6) {
                0 -> "#FF5252".toColorInt() // Red
                1 -> "#448AFF".toColorInt() // Blue
                2 -> "#69F0AE".toColorInt() // Green
                3 -> "#FFAB40".toColorInt() // Orange
                4 -> "#EA80FC".toColorInt() // Purple
                else -> "#FFFF00".toColorInt() // Yellow
            }

            // Create a complex radial gradient
            val colors = intArrayOf(
                baseColor, Color.argb(
                    255, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor)
                ), Color.argb(
                    200,
                    Color.red(baseColor) / 2,
                    Color.green(baseColor) / 2,
                    Color.blue(baseColor) / 2
                )
            )
            val positions = floatArrayOf(0.0f, 0.7f, 1.0f)

            val gradient = RadialGradient(
                size / 2f, size / 2f, size / 1.8f, colors, positions, Shader.TileMode.CLAMP
            )

            val paint = Paint().apply {
                shader = gradient
                isAntiAlias = true
            }

            // Draw a circle with the gradient
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating colored bitmap", e)
            return null
        }
    }


}