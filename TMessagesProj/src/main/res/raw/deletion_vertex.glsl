#version 300 es

layout(location = 0) in vec2 inPosition;
//layout(location = 1) in vec2 inVelocity;
//layout(location = 2) in float inTime;
//layout(location = 3) in float inDuration;

out vec2 outPosition;
//out vec2 outVelocity;
//out float outTime;
//out float outDuration;
out vec2 vTexCoord;

void main() {
    outPosition = inPosition;
    vTexCoord = vec2(inPosition.x / 2.0 + 0.5, -inPosition.y / 2.0 + 0.5);

    gl_Position = vec4(inPosition, 0., 1.);
}
