package com.blazikener.devin.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.pow

/** Mount only while a create is pending. One bounded turn, never a delay before opening Chat. */
@Composable
fun SessionLaunchCue(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val context = LocalContext.current
    val supports3d = remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.deviceConfigurationInfo?.reqGlEsVersion?.let { it >= 0x20000 } == true
    }
    var failed by remember { mutableStateOf(false) }
    Column(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(152.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            if (reduced || failed || !supports3d) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            } else {
                NativeLaunchTiles(Modifier.fillMaxSize(), onFailure = { failed = true })
            }
        }
        Text("Starting session", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Setting up your workspace", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Still welcome illustration. Original jade work tiles; no sensors, breathing or perpetual animation. */
@Composable
fun JadeWorkTiles(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val palettes = listOf(Color(0xFF4A7A59), Color(0xFFD1D9BD), Color(0xFF6EA37A))
        for (layer in 0..2) {
            val y = .58f - layer * .18f
            fun polygon(points: List<Pair<Float, Float>>, color: Color) {
                drawPath(Path().apply {
                    points.forEachIndexed { index, (x, y) ->
                        if (index == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height)
                    }
                    close()
                }, color)
            }
            polygon(listOf(.08f to y, .57f to y + .19f, .57f to y + .28f, .08f to y + .09f), palettes[layer].copy(red = palettes[layer].red * .7f, green = palettes[layer].green * .7f, blue = palettes[layer].blue * .7f))
            polygon(listOf(.57f to y + .19f, .94f to y - .02f, .94f to y + .07f, .57f to y + .28f), palettes[layer])
            polygon(listOf(.08f to y, .46f to y - .21f, .94f to y - .02f, .57f to y + .19f), palettes[layer])
        }
    }
}

@Composable
private fun NativeLaunchTiles(modifier: Modifier, onFailure: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val callback by rememberUpdatedState(onFailure)
    val renderer = remember { TilesRenderer { Handler(Looper.getMainLooper()).post { callback() } } }
    val view = remember(context) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            holder.setFormat(PixelFormat.TRANSLUCENT)
            setZOrderOnTop(true)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    val started = rememberSaveable { SystemClock.uptimeMillis() }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume() else view.onPause()
        onDispose {
            lifecycle.removeObserver(observer)
            view.queueEvent { renderer.release() }
            view.onPause()
        }
    }
    LaunchedEffect(view, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                val elapsed = (SystemClock.uptimeMillis() - started).coerceAtLeast(0)
                renderer.progress = (elapsed / 2200f).coerceIn(0f, 1f)
                view.requestRender()
                if (elapsed >= 2200) break
                withFrameNanos { }
            } while (true)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private class TilesRenderer(private val onFailure: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var progress = 0f
    private var program = 0
    private var buffer = 0
    private var vertexCount = 0
    private var aspect = 1f
    private var failed = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = 0 // A new EGL context has no resources from the previous one.
        buffer = 0
        safely {
            fun shader(type: Int, source: String): Int {
                val shader = glCreateShader(type)
                glShaderSource(shader, source)
                glCompileShader(shader)
                val status = IntArray(1)
                glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) { glDeleteShader(shader); error("Shader unavailable") }
                return shader
            }
            val vertex = shader(GL_VERTEX_SHADER, VERTEX)
            val fragment = try { shader(GL_FRAGMENT_SHADER, FRAGMENT) } catch (e: Exception) { glDeleteShader(vertex); throw e }
            program = glCreateProgram()
            glAttachShader(program, vertex)
            glAttachShader(program, fragment)
            glLinkProgram(program)
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            val linked = IntArray(1)
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0)
            val mesh = launchTilesMesh()
            vertexCount = mesh.size / 9
            val data = ByteBuffer.allocateDirect(mesh.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            data.put(mesh).position(0)
            val names = IntArray(1)
            glGenBuffers(1, names, 0)
            buffer = names[0]
            glBindBuffer(GL_ARRAY_BUFFER, buffer)
            glBufferData(GL_ARRAY_BUFFER, mesh.size * 4, data, GL_STATIC_DRAW)
            glEnable(GL_DEPTH_TEST)
            glDepthFunc(GL_LEQUAL)
            glClearColor(0f, 0f, 0f, 0f)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        aspect = width.toFloat() / height.coerceAtLeast(1)
        glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) = safely {
        if (program == 0) return@safely
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        glUseProgram(program)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        for ((index, name) in listOf("aPosition", "aNormal", "aColour").withIndex()) {
            val location = glGetAttribLocation(program, name)
            check(location >= 0)
            glEnableVertexAttribArray(location)
            glVertexAttribPointer(location, 3, GL_FLOAT, false, 9 * 4, index * 3 * 4)
        }
        val ease = 1f - (1f - progress).pow(3)
        glUniform1f(glGetUniformLocation(program, "uAngle"), -.65f + ease * PI.toFloat() * 1.35f)
        glUniform1f(glGetUniformLocation(program, "uAspect"), aspect)
        glDrawArrays(GL_TRIANGLES, 0, vertexCount)
        check(glGetError() == GL_NO_ERROR)
    }

    fun release() {
        if (buffer != 0) glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (program != 0) glDeleteProgram(program)
        buffer = 0
        program = 0
    }

    private inline fun safely(block: () -> Unit) {
        if (failed) return
        try { block() } catch (_: Exception) {
            release()
            failed = true
            onFailure()
        }
    }

    companion object {
        private const val VERTEX = """
            attribute vec3 aPosition; attribute vec3 aNormal; attribute vec3 aColour;
            uniform float uAngle; uniform float uAspect;
            varying vec3 vNormal; varying vec3 vColour; varying vec3 vPosition;
            void main() {
                float c=cos(uAngle),s=sin(uAngle);
                mat3 ry=mat3(c,0.,-s,0.,1.,0.,s,0.,c);
                float cx=cos(-.43),sx=sin(-.43);
                mat3 rx=mat3(1.,0.,0.,0.,cx,sx,0.,-sx,cx);
                mat3 m=rx*ry; vec3 p=m*aPosition; p.y+=.02;
                vPosition=p; vNormal=normalize(m*aNormal); vColour=aColour;
                p.z-=4.7;
                float near=.1,far=30.,f=2.6;
                gl_Position=vec4(p.x*f/uAspect,p.y*f,(far+near)/(near-far)*p.z+(2.*far*near)/(near-far),-p.z);
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            varying vec3 vNormal; varying vec3 vColour; varying vec3 vPosition;
            void main() {
                vec3 N=normalize(vNormal),L=normalize(vec3(-.7,1.2,2.)),V=normalize(vec3(0.,0.,4.7)-vPosition);
                float diff=max(dot(N,L),0.);
                float spec=pow(max(dot(N,normalize(L+V)),0.),44.);
                vec3 col=vColour*(.43+.57*diff)+vec3(.28)*spec;
                gl_FragColor=vec4(col,1.);
            }
        """
    }
}
