package com.sirandev.photocompare.livephoto

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LivePhotoParser] using synthetic motion-photo fixtures.
 */
class LivePhotoParserTest {

    // ---------- fixture builders ----------

    private fun u32be(value: Int): ByteArray = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun app1Segment(payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + payload
    }

    private fun jpegWithXmp(xmp: String?): ByteArray {
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val sosTail = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02) // empty SOS, then EOI
        val xmpApp1 = xmp?.let {
            val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
            app1Segment(ns + it.toByteArray(Charsets.UTF_8))
        } ?: ByteArray(0)
        val exifPayload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(40)
        val exifApp1 = app1Segment(exifPayload)
        return soi + xmpApp1 + exifApp1 + sosTail + eoi
    }

    private fun mp4Tail(totalSize: Int): ByteArray {
        // minimal mp4: a single ftyp box spanning the whole video part (box starts at video start)
        val boxSize = totalSize
        val header = u32be(boxSize) +
            byteArrayOf(
                'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), // major brand
            )
        val filler = ByteArray((totalSize - header.size).coerceAtLeast(0))
        return header + filler
    }

    private fun withVideo(jpeg: ByteArray, video: ByteArray): ByteArray = jpeg + video

    private fun parse(bytes: ByteArray): LivePhotoInfo =
        LivePhotoParser.parse(ByteArrayInputStream(bytes), bytes.size.toLong())

    // ---------- MicroVideo (Xiaomi) ----------

    @Test
    fun `xiaomi microvideo offset from end`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">
            <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                GCamera:MicroVideoEnabled="True"
                GCamera:MicroVideoVersion="1"
                GCamera:MicroVideoOffset="1000"
                GCamera:MicroVideoPresentationTimestampUs="834414"/>
        </x:xmpmeta>"""
        val jpeg = jpegWithXmp(xmp)
        val video = ByteArray(1000)
        val bytes = withVideo(jpeg, video)
        val info = parse(bytes)
        assertTrue("expected MicroVideo, got $info", info is LivePhotoInfo.MicroVideo)
        info as LivePhotoInfo.MicroVideo
        assertEquals(1000L, info.offsetFromEnd)
        assertEquals(834414L, info.presentationTimestampUs)
        val videoStart = bytes.size - info.offsetFromEnd
        // video area starts with our mp4-ish bytes (all zeros here → just verify range math)
        assertEquals(jpeg.size.toLong(), videoStart)
    }

    @Test
    fun `microvideo disabled is not live`() {
        val xmp = """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MicroVideoEnabled="False" GCamera:MicroVideoOffset="500"/>"""
        val info = parse(jpegWithXmp(xmp))
        assertTrue("expected NotLivePhoto, got $info", info is LivePhotoInfo.NotLivePhoto)
    }

    // ---------- Motion Photo v2 ----------

    @Test
    fun `motion photo v2 two items takes last`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/" xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description GCamera:MotionPhoto="True" GCamera:MotionPhotoVersion="1">
             <Container:Directory rdf:parseType="Container">
              <Container:Item Container:Semantic="Primary" Container:Length="0"/>
              <Container:Item Container:Semantic="MotionPhoto" Container:Length="1000"/>
             </Container:Directory>
            </rdf:Description>
        </x:xmpmeta>"""
        val jpeg = jpegWithXmp(xmp)
        val bytes = withVideo(jpeg, mp4Tail(1000))
        val info = parse(bytes)
        assertTrue("expected MotionPhotoV2, got $info", info is LivePhotoInfo.MotionPhotoV2)
        assertEquals(1000L, (info as LivePhotoInfo.MotionPhotoV2).videoLength)
    }

    @Test
    fun `motion photo v2 three items still takes last`() {
        val xmp = """<rdf:Description GCamera:MotionPhoto="1">
             <Container:Directory>
              <Container:Item Container:Semantic="Primary" Container:Length="0"/>
              <Container:Item Container:Semantic="GainMap" Container:Length="256"/>
              <Container:Item Container:Semantic="MotionPhoto" Container:Length="512"/>
             </Container:Directory>
        </rdf:Description>"""
        val bytes = jpegWithXmp(xmp) + ByteArray(256) + mp4Tail(512)
        val info = parse(bytes)
        assertTrue("expected MotionPhotoV2, got $info", info is LivePhotoInfo.MotionPhotoV2)
        assertEquals(512L, (info as LivePhotoInfo.MotionPhotoV2).videoLength)
    }

    // ---------- tail scan (Huawei / Honor) ----------

    @Test
    fun `huawei tail ftyp is detected`() {
        // no XMP at all
        val jpeg = jpegWithXmp(null)
        val video = mp4Tail(400)
        val bytes = withVideo(jpeg, video)
        val info = parse(bytes)
        assertTrue("expected TailVideo, got $info", info is LivePhotoInfo.TailVideo)
        assertEquals((bytes.size - 400).toLong(), (info as LivePhotoInfo.TailVideo).videoStartOffset)
    }

    @Test
    fun `random ftyp in middle with invalid box size is rejected`() {
        val jpeg = jpegWithXmp(null)
        val junk = ByteArray(64) { 0x41 } + "ftyp".toByteArray() + ByteArray(32)
        val bytes = withVideo(jpeg, junk)
        val info = parse(bytes)
        assertTrue("expected NotLivePhoto, got $info", info is LivePhotoInfo.NotLivePhoto)
    }

    // ---------- negative / robustness ----------

    @Test
    fun `plain jpeg is not live`() {
        val info = parse(jpegWithXmp(null))
        assertTrue("expected NotLivePhoto, got $info", info is LivePhotoInfo.NotLivePhoto)
    }

    @Test
    fun `jpeg without xmp but with disabled motion marker is not live`() {
        val xmp = """<rdf:Description GCamera:MotionPhoto="False"/>"""
        val info = parse(jpegWithXmp(xmp))
        assertTrue("expected NotLivePhoto, got $info", info is LivePhotoInfo.NotLivePhoto)
    }

    @Test
    fun `corrupted jpeg terminates safely`() {
        // SOI immediately followed by SOS with no segments
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte())
        val info = parse(bytes)
        assertTrue("expected NotLivePhoto, got $info", info is LivePhotoInfo.NotLivePhoto)
    }

    @Test
    fun `exif app1 before xmp app1 still parses`() {
        val xmp = """<rdf:Description GCamera:MotionPhoto="True">
             <Container:Directory>
              <Container:Item Container:Semantic="Primary" Container:Length="0"/>
              <Container:Item Container:Semantic="MotionPhoto" Container:Length="64"/>
             </Container:Directory>
        </rdf:Description>"""
        // jpegWithXmp already places a fake EXIF APP1 AFTER the XMP; build one where EXIF comes first
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val exifPayload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(40)
        val exifApp1 = app1Segment(exifPayload)
        val ns = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val xmpApp1 = app1Segment(ns + xmp.toByteArray(Charsets.UTF_8))
        val bytes = soi + exifApp1 + xmpApp1 + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x02) + mp4Tail(64)
        val info = parse(bytes)
        assertTrue("expected MotionPhotoV2, got $info", info is LivePhotoInfo.MotionPhotoV2)
        assertEquals(64L, (info as LivePhotoInfo.MotionPhotoV2).videoLength)
    }

    @Test
    fun `non jpeg is rejected`() {
        val info = LivePhotoParser.parse(ByteArrayInputStream(ByteArray(100)), 100L)
        assertTrue(info is LivePhotoInfo.NotLivePhoto)
    }
}
