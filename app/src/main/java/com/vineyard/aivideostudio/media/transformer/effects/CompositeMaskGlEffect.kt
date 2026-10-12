package com.vineyard.aivideostudio.media.transformer.effects

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleFrameGlShaderProgram

/**
 * High-performance GPU-accelerated OpenGL ES 2.0 Effect for Media3 Transformer.
 * Performs dynamic feathered masking for clones, traveling mattes (near-miss stunts),
 * wipes, and object transitions directly on hardware surfaces.
 */
@UnstableApi
class CompositeMaskGlEffect(
    val maskType: MaskType = MaskType.LINEAR_HORIZONTAL,
    val startSplitPos: Float = 0.5f,
    val endSplitPos: Float = 0.5f,
    val feather: Float = 0.04f,
    val isInverted: Boolean = false,
    val rectBounds: FloatArray = floatArrayOf(0f, 0f, 1f, 1f), // xMin, yMin, xMax, yMax
    val startTimeUs: Long = 0L,
    val endTimeUs: Long = Long.MAX_VALUE
) : GlEffect {

    enum class MaskType(val typeId: Float) {
        LINEAR_HORIZONTAL(0.0f), // Top vs Bottom split (e.g. Toddler & Beanbag)
        LINEAR_VERTICAL(1.0f),   // Left vs Right split (e.g. Talking to your Clone)
        RECTANGLE(2.0f),         // Bounding Box matte
        RADIAL_CIRCLE(3.0f)      // Circular spotlight / portal matte
    }

    @OptIn(UnstableApi::class)
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CompositeMaskShaderProgram(context, useHdr, this)
    }
}

@UnstableApi
internal class CompositeMaskShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val effect: CompositeMaskGlEffect
) : SingleFrameGlShaderProgram(useHdr) {

    private val glProgram: GlProgram

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoords;
            void main() {
                gl_Position = aFramePosition;
                vTexCoords = (aFramePosition.xy + vec2(1.0, 1.0)) * 0.5;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoords;
            uniform sampler2D uTexSampler;
            uniform float uMaskType;
            uniform float uSplitPos;
            uniform float uFeather;
            uniform float uInvert;
            uniform float uIsActive;
            uniform vec4 uRectBounds;

            void main() {
                vec4 baseColor = texture2D(uTexSampler, vTexCoords);
                if (uIsActive < 0.5) {
                    gl_FragColor = baseColor;
                    return;
                }

                float alpha = 1.0;
                float feather = max(uFeather, 0.0001);

                if (uMaskType < 0.5) {
                    // Linear Horizontal Split
                    alpha = smoothstep(uSplitPos - feather, uSplitPos + feather, vTexCoords.y);
                } else if (uMaskType < 1.5) {
                    // Linear Vertical Split
                    alpha = smoothstep(uSplitPos - feather, uSplitPos + feather, vTexCoords.x);
                } else if (uMaskType < 2.5) {
                    // Rectangle Box
                    float left = smoothstep(uRectBounds.x - feather, uRectBounds.x + feather, vTexCoords.x);
                    float right = 1.0 - smoothstep(uRectBounds.z - feather, uRectBounds.z + feather, vTexCoords.x);
                    float top = smoothstep(uRectBounds.y - feather, uRectBounds.y + feather, vTexCoords.y);
                    float bottom = 1.0 - smoothstep(uRectBounds.w - feather, uRectBounds.w + feather, vTexCoords.y);
                    alpha = left * right * top * bottom;
                } else {
                    // Radial Circle
                    float dist = distance(vTexCoords, vec2(0.5, 0.5));
                    alpha = 1.0 - smoothstep(uSplitPos - feather, uSplitPos + feather, dist);
                }

                if (uInvert > 0.5) {
                    alpha = 1.0 - alpha;
                }

                gl_FragColor = vec4(baseColor.rgb * alpha, baseColor.a * alpha);
            }
        """
    }

    init {
        glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val isActive = presentationTimeUs in effect.startTimeUs..effect.endTimeUs

        // Dynamically interpolate split position over time if animating
        val currentSplitPos = if (isActive && effect.endTimeUs > effect.startTimeUs) {
            val progress = ((presentationTimeUs - effect.startTimeUs).toFloat() /
                    (effect.endTimeUs - effect.startTimeUs).toFloat()).coerceIn(0f, 1f)
            effect.startSplitPos + (effect.endSplitPos - effect.startSplitPos) * progress
        } else {
            effect.startSplitPos
        }

        glProgram.use()
        glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        glProgram.setFloatUniform("uMaskType", effect.maskType.typeId)
        glProgram.setFloatUniform("uSplitPos", currentSplitPos)
        glProgram.setFloatUniform("uFeather", effect.feather)
        glProgram.setFloatUniform("uInvert", if (effect.isInverted) 1.0f else 0.0f)
        glProgram.setFloatUniform("uIsActive", if (isActive) 1.0f else 0.0f)
        glProgram.setFloatsUniform("uRectBounds", effect.rectBounds)
        glProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: Exception) {}
    }
}