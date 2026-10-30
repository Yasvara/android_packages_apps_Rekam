/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.rekam.probe

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S2: a VD rendering straight into the encoder is moved to a GL compositor with
 * VirtualDisplay.setSurface(); the encoder keeps running. Reports the PTS gap at the cut.
 */
@RunWith(AndroidJUnit4::class)
class S2HandoverTest {
    @Before fun up() = Proj.startFgs()
    @After fun down() = Proj.stopFgs()

    private fun log(line: String) = Probe.log("S2", line)

    @Test
    fun directToComposited() {
        val (dw, dh) = Proj.displaySize()
        val w = dw / 2 and -2
        val h = dh / 2 and -2
        val codec = MediaCodec.createByCodecName("c2.qti.hevc.encoder")
        codec.configure(videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h, 60, 10_000_000, BITRATE_MODE_VBR), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = codec.createInputSurface()
        codec.start()
        val pts = ArrayList<Long>()
        val drain = Thread {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val i = codec.dequeueOutputBuffer(info, 50_000)
                if (i >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) synchronized(pts) { pts += info.presentationTimeUs }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }.apply { start() }

        val removeOverlay = Proj.overlay(size = 300, animate = true)
        val p = Proj.create()
        val vd = Proj.virtualDisplay(p, w, h, encSurface)
        SystemClock.sleep(1500)
        val directFrames = synchronized(pts) { pts.size }

        val gl = GlBlit(encSurface, w, h)
        val cutNs = System.nanoTime() // CLOCK_MONOTONIC, same clock as SurfaceFlinger's buffer timestamps
        vd.surface = gl.input
        val attach = gl.attachEncoder()
        SystemClock.sleep(2500)

        codec.signalEndOfInputStream()
        drain.join(5000)
        vd.release(); p.stop(); gl.release(); removeOverlay()
        codec.stop(); codec.release()

        val sorted = synchronized(pts) { pts.sorted() }
        val gaps = sorted.zipWithNext().map { (a, b) -> b - a }
        val cutUs = cutNs / 1000
        val cutGap = sorted.zipWithNext().firstOrNull { (a, b) -> a <= cutUs && b > cutUs }?.let { (a, b) -> b - a }
        log("direct=$directFrames total=${sorted.size} composited=${gl.drawn} attach=$attach maxGapMs=${(gaps.maxOrNull() ?: 0) / 1000} cutGapMs=${cutGap?.div(1000)} medianGapMs=%.1f".format((gaps.sorted().getOrNull(gaps.size / 2) ?: 0) / 1000.0))
    }

    /** Minimal compositor: VD → OES texture → encoder surface, timestamps passed through. */
    private class GlBlit(private val target: Surface, private val w: Int, private val h: Int) {
        private val thread = HandlerThread("probe-gl").apply { start() }
        private val handler = Handler(thread.looper)
        private val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private lateinit var ctx: android.opengl.EGLContext
        private lateinit var config: EGLConfig
        private lateinit var pbuffer: EGLSurface
        private var window: EGLSurface? = null
        private lateinit var st: SurfaceTexture
        lateinit var input: Surface
        @Volatile var drawn = 0
        private var program = 0
        private val mtx = FloatArray(16)

        init { onGl { setup() } }

        private fun onGl(block: () -> Unit) {
            val done = CountDownLatch(1)
            handler.post { block(); done.countDown() }
            done.await(3, TimeUnit.SECONDS)
        }

        private fun setup() {
            val v = IntArray(2); EGL14.eglInitialize(dpy, v, 0, v, 1)
            val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
            config = cfgs[0]!!
            ctx = EGL14.eglCreateContext(dpy, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            pbuffer = EGL14.eglCreatePbufferSurface(dpy, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            program = program(VS, FS)
            st = SurfaceTexture(tex[0]).apply {
                setDefaultBufferSize(w, h)
                setOnFrameAvailableListener({ draw() }, handler)
            }
            input = Surface(st)
        }

        /** Connects EGL to the encoder surface the VD just let go of; returns how long it took. */
        fun attachEncoder(): String {
            var result = "?"
            onGl {
                val t0 = SystemClock.elapsedRealtimeNanos()
                var s: EGLSurface = EGL14.EGL_NO_SURFACE
                for (attempt in 0 until 50) {
                    s = EGL14.eglCreateWindowSurface(dpy, config, target, intArrayOf(EGL14.EGL_NONE), 0)
                    if (s != EGL14.EGL_NO_SURFACE) { result = "ok after ${attempt} retries ${(SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000}ms"; break }
                    result = "fail egl=0x%x".format(EGL14.eglGetError())
                    SystemClock.sleep(10)
                }
                if (s != EGL14.EGL_NO_SURFACE) window = s
            }
            return result
        }

        private fun draw() {
            st.updateTexImage()
            val win = window ?: return
            EGL14.eglMakeCurrent(dpy, win, win, ctx)
            st.getTransformMatrix(mtx)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, mtx, 0)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(dpy, win, st.timestamp)
            EGL14.eglSwapBuffers(dpy, win)
            drawn++
        }

        fun release() = onGl {
            st.release(); input.release()
            window?.let { EGL14.eglDestroySurface(dpy, it) }
            EGL14.eglDestroySurface(dpy, pbuffer)
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(dpy, ctx)
            thread.quitSafely()
        }

        private fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }

        companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
            val QUAD = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            const val VS = "attribute vec2 aPos; uniform mat4 uTex; varying vec2 vUv;\n" +
                "void main(){ gl_Position=vec4(aPos,0.,1.); vUv=(uTex*vec4(aPos*.5+.5,0.,1.)).xy; }"
            const val FS = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\n" +
                "uniform samplerExternalOES uS; varying vec2 vUv; void main(){ gl_FragColor=texture2D(uS,vUv); }"
        }
    }
}
