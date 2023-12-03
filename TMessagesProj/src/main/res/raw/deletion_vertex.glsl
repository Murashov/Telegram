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

uniform float deltaTime;

float rand(vec2 n) {
    return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 4375.5453);
}

vec2 initVelocity() {
    float direction = rand(vec2(inSeed * 2.31, inSeed + 14.145)) * (3.14159265 * 2.0);
    float velocity = (0.1 + rand(vec2(inSeed / 61.2, inSeed - 1.22)) * (0.2 - 0.1)) * 1.0; //420
    return vec2(cos(direction) * velocity, sin(direction) * velocity);
}

void main() {
    if (inVelocity.xy == vec2(0.0, 0.0) && inTexCoord == vec2(0.0, 0.0)) { // TODO change check
        outTexCoord = vec2(inPosition.x / 2.0 + 0.5, -inPosition.y / 2.0 + 0.5);
        outVelocity = initVelocity();
        outLifetime = inLifetime;
    } else {
        outTexCoord = inTexCoord;
        outVelocity = inVelocity;
        outLifetime = inLifetime;
    }
    outPosition = inPosition + inVelocity * deltaTime;
    outSeed = inSeed;

    vTexCoord = outTexCoord;
    alpha = 1.0;
    gl_Position = vec4(inPosition, 0., 1.);
}
