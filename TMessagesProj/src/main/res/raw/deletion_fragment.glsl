#version 300 es

precision highp float;

out vec4 fragColor;

in vec2 vTexCoord;
in float alpha;

uniform sampler2D uTexture;
uniform vec2 localPointSize;

void main() {
    vec2 offsetInPoint = localPointSize * (gl_PointCoord - 0.5);
    vec4 color = texture(uTexture, vTexCoord + offsetInPoint);
    fragColor = vec4(color.rgb, color.a * alpha);
}
