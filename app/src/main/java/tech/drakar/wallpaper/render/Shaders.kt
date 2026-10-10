package tech.drakar.wallpaper.render

/** GLSL ES 1.00 sources. The globe, fill and line shaders are those of the page (src/front/gl/shaders.js). */
object Shaders {
    const val GLOBE_VS = "attribute vec2 p; varying vec2 v; void main(){ v = p; gl_Position = vec4(p, 0., 1.); }"
    const val GLOBE_FS = """
precision highp float;
varying vec2 v;
uniform vec2 res; uniform vec2 center; uniform float R; uniform float lon0, lat0; uniform float climate;
uniform sampler2D base; uniform sampler2D mask;
const float PI = 3.14159265;
vec3 landCol(float t) {
  if (t < 3.5) return vec3(0.137, 0.165, 0.200);     // plain: hairline #232a33 (charte, theme nuit)
  if (t < 4.5) return vec3(0.170, 0.198, 0.233);     // hills
  if (t < 5.5) return vec3(0.215, 0.243, 0.278);     // mountain
  return vec3(0.200, 0.232, 0.268);                  // ice
}
void main() {
  vec2 px = v * res * .5;
  vec2 q = (px - center) / R;
  float d2 = dot(q, q);
  vec3 bg = vec3(0.027, 0.035, 0.047);
  float tint = climate;
  // the climate, as a hairline ring around the globe: brass when the index rises, marine-text when it falls
  vec3 ringC = tint >= 0. ? vec3(0.831, 0.647, 0.227) : vec3(0.663, 0.741, 0.863);
  if (d2 > 1.0) { float d = (sqrt(d2) - 1.0) * R; float ring = 1.0 - smoothstep(0.6, 1.6, abs(d - 6.0)); gl_FragColor = vec4(mix(bg, ringC, ring * (0.12 + 0.5 * abs(tint))), 1.); return; }
  float z = sqrt(1.0 - d2);
  float cl0 = cos(lat0), sl0 = sin(lat0);
  float lat = asin(clamp(q.y * cl0 + z * sl0, -1., 1.));
  float lon = lon0 + atan(q.x, cl0 * z - q.y * sl0);
  vec2 uv = vec2(mod(lon / (2. * PI) + .5, 1.), 0.5 - lat / PI);
  float fv = floor(texture2D(base, uv).r * 255. + .5);
  float t = floor(fv / 32.), lvl = mod(fv, 32.) / 2.;
  float land = texture2D(mask, uv).r;
  vec3 sea = (t >= 1.5 || t == 1.) ? vec3(0.082, 0.125, 0.184) : vec3(0.059, 0.075, 0.098);   // shelf: marine-tint · deep sea: surface-200
  vec3 c = mix(sea, landCol(t + 1.) * (1.0 + lvl * 0.035), land);
  if (t > 4.5 && land > 0.5) c = landCol(6.);
  float gla = abs(fract(lat / PI * 12. + .5) - .5), glo = abs(fract(lon / PI * 12. + .5) - .5);
  float gw = 0.012 * (1.0 + 3.0 * (1.0 - z));
  float grat = (1.0 - smoothstep(0.0, gw, gla)) + (1.0 - smoothstep(0.0, gw, glo));
  c += vec3(0.925, 0.922, 0.902) * grat * 0.035;
  float shade = 0.55 + 0.45 * pow(z, 0.6);
  c *= shade;
  float aa = smoothstep(1.0, 1.0 - 2.0 / R, d2);
  gl_FragColor = vec4(mix(bg, c, aa), 1.);
}"""

    const val QUAD_VS = "attribute vec2 p; void main(){ gl_Position = vec4(p, 0., 1.); }"
    const val WHITE_FS = "precision mediump float; void main(){ gl_FragColor = vec4(1.); }"
    const val FILL_VS = "attribute vec2 ll; uniform float off; void main(){ gl_Position = vec4((ll.x + off) / 180., ll.y / 90., 0., 1.); }"

    // lines: segments extruded in the vertex shader; colour per vertex (constant attribute for the static sets)
    const val LINE_VS = """
attribute vec3 a0, a1; attribute vec2 corner; attribute vec4 color;
uniform mat3 M; uniform vec2 res, center; uniform float R, hw;
varying float vz; varying vec4 vc;
void main() {
  vec3 q0 = M * a0, q1 = M * a1;
  vec2 s0 = center + R * vec2(q0.x, -q0.y), s1 = center + R * vec2(q1.x, -q1.y);
  vec2 d = s1 - s0; float L = length(d); d = L > 0.0 ? d / L : vec2(1., 0.);
  vec2 n = vec2(-d.y, d.x);
  vec2 p = (corner.x < 0.5 ? s0 : s1) + n * corner.y * hw;
  vz = min(q0.z, q1.z); vc = color;
  gl_Position = vec4(p.x / res.x * 2. - 1., 1. - p.y / res.y * 2., 0., 1.);
}"""
    const val LINE_FS = "precision mediump float; varying float vz; varying vec4 vc; void main(){ if (vz < 0.0) discard; gl_FragColor = vec4(vc.rgb, vc.a * smoothstep(0.0, 0.12, vz)); }"

    // life: one point sprite per group, settlement or city; kind 0 disc · 1 ring · 2 square outline
    const val POINT_VS = """
attribute vec3 pos; attribute vec4 color; attribute vec3 style;
uniform mat3 M; uniform vec2 res, center; uniform float R;
varying vec4 vc; varying vec2 vs;
void main() {
  vec3 q = M * pos;
  if (q.z < 0.0) { gl_Position = vec4(2., 2., 2., 1.); gl_PointSize = 0.0; vc = vec4(0.); vs = vec2(0.); return; }
  vec2 s = center + R * vec2(q.x, -q.y);
  gl_Position = vec4(s.x / res.x * 2. - 1., 1. - s.y / res.y * 2., 0., 1.);
  gl_PointSize = style.x * (0.6 + 0.4 * q.z);
  vc = vec4(color.rgb, color.a * (0.3 + 0.7 * q.z)); vs = style.yz;
}"""
    const val POINT_FS = """
precision mediump float;
varying vec4 vc; varying vec2 vs;
void main() {
  vec2 p = gl_PointCoord * 2. - 1.;
  float a;
  if (vs.x < 0.5) { float d = length(p); a = 1.0 - smoothstep(0.75, 1.0, d); }
  else if (vs.x < 1.5) { float d = length(p); a = smoothstep(1.0 - vs.y * 2.0, 1.0 - vs.y, d) * (1.0 - smoothstep(1.0 - vs.y * 0.3, 1.0, d)); }
  else { float d = max(abs(p.x), abs(p.y)); a = step(1.0 - vs.y, d) * step(d, 0.98); }
  if (a <= 0.01) discard;
  gl_FragColor = vec4(vc.rgb, vc.a * a);
}"""

    // text overlay: a textured quad in pixels
    const val TEXT_VS = "attribute vec2 p; attribute vec2 uv; uniform vec2 res; varying vec2 vuv; void main(){ vuv = uv; gl_Position = vec4(p.x / res.x * 2. - 1., 1. - p.y / res.y * 2., 0., 1.); }"
    const val TEXT_FS = "precision mediump float; varying vec2 vuv; uniform sampler2D tex; void main(){ gl_FragColor = texture2D(tex, vuv); }"
}
