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
    vTexCoord = inPosition;

    gl_PointSize = 30.0;
    gl_Position = vec4(inPosition, 0., 1.);
}
