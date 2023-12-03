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
uniform vec2 maxSpeed;
uniform float acceleration;

float rand(vec2 n) {
    return fract(sin(dot(n, vec2(12.9898, 4.1414))) * 4375.5453);
}

vec2 initVelocity() {
    float direction = rand(vec2(inSeed * 2.31, inSeed + 14.145)) * (3.14159265 * 2.0);
    float velocityValue = (0.1 + rand(vec2(inSeed / 61.2, inSeed - 1.22)) * (0.2 - 0.1));
    vec2 velocity = vec2(velocityValue * maxSpeed.x, velocityValue * maxSpeed.y);
    return vec2(cos(direction) * velocity.x, sin(direction) * velocity.y);
}

float initLifetime() {
    float min = 0.7;
    float max = 1.5;
    return min + rand(vec2(inSeed - 1.2, inSeed * 153.5)) * (max - min);
}

void main() {
    if (inVelocity.xy == vec2(0.0, 0.0) && inTexCoord == vec2(0.0, 0.0)) { // TODO change check
        outTexCoord = vec2(inPosition.x / 2.0 + 0.5, -inPosition.y / 2.0 + 0.5);
        outVelocity = initVelocity();
        outLifetime = initLifetime();
    } else {
        outTexCoord = inTexCoord;
        outVelocity = inVelocity + vec2(0.0, deltaTime * acceleration);
        outLifetime = max(0.0, inLifetime - deltaTime);
    }
    outPosition = inPosition + inVelocity * deltaTime;
    outSeed = inSeed;

    vTexCoord = outTexCoord;
    alpha = max(0.0, min(0.3, outLifetime) / 0.3);
    gl_Position = vec4(inPosition, 0.0, 1.0);
}
