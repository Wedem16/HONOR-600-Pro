#version 300 es
precision highp float;

layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec4 aTexCoord;

uniform mat4 uMvpMatrix;
uniform mat4 uTexMatrix;

out vec2 vTexCoord;
out vec2 vScreenUv;

void main() {
    gl_Position = uMvpMatrix * aPosition;
    vTexCoord = (uTexMatrix * aTexCoord).xy;
    vScreenUv = aPosition.xy * 0.5 + 0.5;
}
