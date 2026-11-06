#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

#if !defined(NO_OVERLAY) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler1;
#endif

#if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
uniform sampler2D Sampler2;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

float extendedRenderFog(float distance, float fogStart, float fogEnd) {
    if (distance <= fogStart) return 0.0;
    if (distance <= fogEnd) return (distance - fogStart) / max(fogEnd - fogStart, 0.001);

    // Keep the transition at the vanilla horizon, then reveal the LOD over two chunks.
    float reveal = clamp((distance - fogEnd) / 32.0, 0.0, 1.0);
    reveal = reveal * reveal * (3.0 - 2.0 * reveal);
    return 1.0 - reveal;
}

vec4 calculateFinalColor(vec4 color) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    #ifndef EMISSIVE
    color *= lightMapColor;
    #endif

    float renderFog = extendedRenderFog(cylindricalVertexDistance,
            FogRenderDistanceStart, FogRenderDistanceEnd);
    float environmentalFog = linear_fog_value(sphericalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd);
    float fogAmount = max(renderFog, environmentalFog);
    color.rgb = mix(color.rgb, FogColor.rgb, fogAmount * FogColor.a);
    return color;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) discard;
    #endif

    #ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    #else
    vec4 faceVertexColor = vertexColor;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifndef OIT_ALPHA_ONLY
    fragColor = calculateFinalColor(color);
    #endif
}
