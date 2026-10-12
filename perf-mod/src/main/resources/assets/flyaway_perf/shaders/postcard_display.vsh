#version 330 core
// Surface of the nested portal Q; positions are relative to the current camera.
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;   // current projection * modelView (camera rotation only)
out vec3 vRel;
void main() {
    vRel = aPos;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
