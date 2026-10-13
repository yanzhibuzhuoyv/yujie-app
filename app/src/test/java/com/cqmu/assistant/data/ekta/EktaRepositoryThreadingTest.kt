package com.cqmu.assistant.data.ekta

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EktaRepositoryThreadingTest {
    @Test
    fun responseBodyIsConsumedOffCallingThreadAndClosed() = runBlocking {
        val callerThread = Thread.currentThread().name
        val readThread = AtomicReference<String>()
        val closed = AtomicBoolean(false)
        val payload = """{"code":0,"data":{}}"""
        val response = response(
            code = 200,
            source = recordingSource(payload, readThread, closed).buffer()
        )

        val parsed = TestCall(response).awaitEktaJson()

        assertEquals(0, parsed.getInt("code"))
        assertNotEquals(callerThread, readThread.get())
        assertTrue(closed.get())
    }

    @Test
    fun errorResponseIsClosed() = runBlocking {
        val closed = AtomicBoolean(false)
        val response = response(
            code = 500,
            source = recordingSource("error", AtomicReference(), closed).buffer()
        )

        runCatching { TestCall(response).awaitEktaJson() }

        assertTrue(closed.get())
    }

    @Test
    fun cancellationCancelsCallAndClosesBlockingResponse() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val blockingSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                started.countDown()
                released.await(2, TimeUnit.SECONDS)
                throw IOException("cancelled")
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {
                closed.set(true)
                released.countDown()
            }
        }
        val call = TestCall(
            response(code = 200, source = blockingSource.buffer()),
            onCancel = { released.countDown() }
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitEktaJson() }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(closed.get())
    }

    private fun response(code: Int, source: BufferedSource): Response {
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = source
        }
        return Response.Builder()
            .request(REQUEST)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body)
            .build()
    }

    private fun recordingSource(
        payload: String,
        readThread: AtomicReference<String>,
        closed: AtomicBoolean
    ): Source {
        val buffer = Buffer().writeUtf8(payload)
        return object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readThread.compareAndSet(null, Thread.currentThread().name)
                return buffer.read(sink, byteCount)
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {
                closed.set(true)
                buffer.close()
            }
        }
    }

    private class TestCall(
        private val response: Response,
        private val onCancel: () -> Unit = {}
    ) : Call {
        private val cancelled = AtomicBoolean(false)

        override fun request(): Request = REQUEST
        override fun execute(): Response = error("synchronous execution is not used")
        override fun enqueue(responseCallback: Callback) {
            Thread { responseCallback.onResponse(this, response) }.start()
        }

        override fun cancel() {
            cancelled.set(true)
            onCancel()
        }

        override fun isExecuted(): Boolean = true
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = TestCall(response, onCancel)
    }

    private companion object {
        val REQUEST: Request = Request.Builder()
            .url("https://ekta.cqmu.edu.cn/")
            .build()
    }
}
