#version 450
// The video's picture over the whole target: one triangle past the corners, reading the buffer's
// visible part (rect: its corner and size in the buffer) turned upright (turn: a 2x2 rotation).
layout(push_constant) uniform Crop { vec4 rect; vec4 turn; } crop;
layout(location = 0) out vec2 uv;
void main() {
    vec2 pos = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    vec2 c = pos - 0.5;
    vec2 r = vec2(crop.turn.x * c.x + crop.turn.y * c.y, crop.turn.z * c.x + crop.turn.w * c.y) + 0.5;
    uv = crop.rect.xy + r * crop.rect.zw;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
