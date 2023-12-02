#version 300 es

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in vec2 inVelocity;
layout(location = 3) in float inLifetime;
layout(location = 4) in float inSeed;

out vec2 outPosition;
out vec2 outTexCoord;
out vec2 outVelocity;
out float outLifetime;
out float outSeed;

out vec2 vTexCoord;
out float alpha;

uniform bool isInitilized;

void main() {
    if (!isInitilized) {
        // TODO Init
    }
    outPosition = inPosition;
    outTexCoord = inTexCoord;
    outVelocity = inVelocity;
    outLifetime = inLifetime;
    outSeed = inSeed;

    vTexCoord = vec2(inPosition.x / 2.0 + 0.5, -inPosition.y / 2.0 + 0.5);
    alpha = 1.0;
    gl_Position = vec4(inPosition, 0., 1.);
}
