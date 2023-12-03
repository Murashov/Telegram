#version 300 es

precision highp float;

out vec4 fragColor;

in vec2 vTexCoord;
in float alpha;

uniform sampler2D uTexture;
uniform float time;

void main() {
    vec4 color = texture(uTexture, vTexCoord);
    if (color.a == 0.0) {
        fragColor = color;
    } else {
        fragColor = vec4(color.rgb, alpha);
    }
}
