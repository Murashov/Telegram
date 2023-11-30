#version 300 es

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inVelocity;
layout(location = 2) in float inTime;
layout(location = 3) in float inDuration;

out vec2 outPosition;
out vec2 outVelocity;
out float outTime;
out float outDuration;

void main() {
    outPosition = inPosition;
    outVelocity = inVelocity;
    outTime = inTime;
    outDuration = inDuration;

    gl_PointSize = 3.0;
    gl_Position = vec4(inPosition, 0., 1.);
}
