package ru.vgastream.maxvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaxWireInteropTest {
    private val fixture = """M0FD-TUNNEL-V1:{"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","i":0,"n":1,"sha256":"827c7c3cca03dc94e87b433081d6a164e239b412af0da79e6abe936132589b18","data":"eyJ2ZXJzaW9uIjoxLCJraW5kIjoicmVzcG9uc2UiLCJyZXF1ZXN0X2lkIjoiYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEiLCJwYXlsb2FkIjoiVFZneU1URXhNVEV4TVRFeE1URXhYWlFhTi9ac2FLdllXSlZxWkZ2NGl0eUM0QnBCMTZ5bHJ4eEtoMGE3ak5hZUFjaEs1OGZUYXY0QlpFV3d1eGcvNnJnWVU5K0lNdz09Iiwic2VuZGVyIjoiaG9zdCIsInJlY2lwaWVudCI6Im1vYmlsZSJ9"}"""

    @Test fun canDecryptRealPythonMx2Envelope() {
        val codec = MaxWireCodec("t".repeat(64))
        val reply = codec.accept(fixture)
        assertNotNull("Python-generated MX2 frame rejected", reply)
        assertEquals("a".repeat(32), reply!!.id)
        assertEquals("response", reply.kind)
        assertEquals("""{"v":1,"status":"ok","data":"SEVMTE8="}""",
            String(reply.payload, Charsets.US_ASCII))
        assertEquals(null, codec.accept(fixture)) // replay rejected
    }

    @Test fun generatesBoundedRequestFramesForSameAccount() {
        val codec = MaxWireCodec("t".repeat(64))
        val (rid, frames) = codec.request("""{"v":1,"action":"open","host":"example.com","port":443}"""
            .toByteArray(Charsets.US_ASCII))
        assertEquals(32, rid.length)
        assertTrue(frames.isNotEmpty() && frames.all { it.length <= 4000 &&
            it.startsWith("M0FD-TUNNEL-V1:") })
    }
}
