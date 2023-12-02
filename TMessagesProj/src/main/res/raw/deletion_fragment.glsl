#version 300 es

precision highp float;

out vec4 fragColor;

in vec2 vTexCoord;
uniform sampler2D uTexture;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
