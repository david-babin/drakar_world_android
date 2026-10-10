package tech.drakar.wallpaper.render

import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** small OpenGL ES helpers */
class Program(vs: String, fs: String) {
    val id: Int = glCreateProgram()
    private val uniforms = HashMap<String, Int>()
    private val attributes = HashMap<String, Int>()

    init {
        glAttachShader(id, shader(GL_VERTEX_SHADER, vs)); glAttachShader(id, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(id)
        val ok = IntArray(1); glGetProgramiv(id, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + glGetProgramInfoLog(id) }
    }

    fun u(name: String) = uniforms.getOrPut(name) { glGetUniformLocation(id, name) }
    fun a(name: String) = attributes.getOrPut(name) { glGetAttribLocation(id, name) }
    fun use() = glUseProgram(id)

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type); glShaderSource(s, src); glCompileShader(s)
        val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compile: " + glGetShaderInfoLog(s) }
        return s
    }
}

fun floats(a: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).apply { position(0) }
fun ints(a: IntArray): IntBuffer = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).apply { position(0) }

fun genBuffer(): Int { val b = IntArray(1); glGenBuffers(1, b, 0); return b[0] }
fun genTexture(): Int { val b = IntArray(1); glGenTextures(1, b, 0); return b[0] }
fun deleteBuffer(id: Int) { if (id != 0) glDeleteBuffers(1, intArrayOf(id), 0) }

/** a growable float array, to build vertex data without boxing */
class FloatList(capacity: Int = 1024) {
    var data = FloatArray(capacity); private set
    var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    fun add(vararg v: Float) { for (x in v) add(x) }
    fun clear() { size = 0 }
    fun toArray() = data.copyOf(size)
}
