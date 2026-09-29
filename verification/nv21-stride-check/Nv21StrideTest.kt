// Nv21StrideTest.kt
//
// Deterministic, synthetic-data verification of the exact stride-handling
// logic shipped in CameraStreamService.kt (imageProxyToJpeg /
// copyPlaneRespectingStride). Both functions below are copy-pasted
// byte-for-byte from the shipped file (only the ImageProxy.Plane parameter
// type is replaced with a plain ByteBuffer+rowStride+pixelStride triple,
// since ImageProxy/Image.Plane are Android framework types that cannot be
// instantiated outside an Android runtime/emulator — this environment has
// neither). No Android APIs are used anywhere in the actual algorithm
// (ImageFormat/YuvImage/JPEG compression are NOT part of what's under test
// here — only the stride-aware byte-copy math is, since that's where the
// Pass 3 defect and fix were).
//
// This is a pure-JVM check run with the real Kotlin compiler (1.9.24,
// downloaded from GitHub's release CDN, an allowed domain in this sandbox —
// no Android SDK or emulator was used or needed for this specific test).

import java.nio.ByteBuffer

// ─── Exact copy of CameraStreamService.copyPlaneRespectingStride ───────────
fun copyPlaneRespectingStride(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    dest: ByteArray,
    destOffset: Int
) {
    var outIndex = destOffset
    if (pixelStride == 1 && rowStride == width) {
        val duplicate = buffer.duplicate()
        duplicate.position(0)
        duplicate.get(dest, destOffset, width * height)
        return
    }
    for (row in 0 until height) {
        for (col in 0 until width) {
            dest[outIndex++] = buffer.get(row * rowStride + col * pixelStride)
        }
    }
}

// ─── Exact copy of the chroma-interleave loop from imageProxyToJpeg ────────
fun interleaveChroma(
    vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int,
    uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
    width: Int, height: Int,
    nv21: ByteArray, startOffset: Int
) {
    val chromaWidth = width / 2
    val chromaHeight = height / 2
    var offset = startOffset
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val vIndex = row * vRowStride + col * vPixelStride
            val uIndex = row * uRowStride + col * uPixelStride
            nv21[offset++] = vBuffer.get(vIndex)
            nv21[offset++] = uBuffer.get(uIndex)
        }
    }
}

var failures = 0
var passes = 0

fun check(name: String, actual: ByteArray, expected: ByteArray) {
    if (actual.contentEquals(expected)) {
        println("PASS: $name")
        passes++
    } else {
        println("FAIL: $name")
        println("  expected: ${expected.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
        println("  actual:   ${actual.joinToString(",") { (it.toInt() and 0xFF).toString() }}")
        failures++
    }
}

fun main() {
    // ── Case A: tightly packed Y plane (fast path: pixelStride=1, rowStride=width) ──
    run {
        val width = 4; val height = 3
        val src = byteArrayOf(0,1,2,3, 4,5,6,7, 8,9,10,11)
        val buf = ByteBuffer.wrap(src)
        val dest = ByteArray(width * height)
        copyPlaneRespectingStride(buf, width, 1, width, height, dest, 0)
        check("A. tightly packed Y plane (fast path)", dest, src)
    }

    // ── Case B: row-padded Y plane (rowStride > width, pixelStride=1) ──────
    run {
        val width = 4; val height = 3; val rowStride = 6
        // Each row has 4 real pixels + 2 padding bytes (0xFF sentinel, must NOT
        // appear anywhere in the output).
        val src = byteArrayOf(
            0,1,2,3, -1,-1,
            4,5,6,7, -1,-1,
            8,9,10,11, -1,-1
        )
        val buf = ByteBuffer.wrap(src)
        val dest = ByteArray(width * height)
        copyPlaneRespectingStride(buf, rowStride, 1, width, height, dest, 0)
        val expected = byteArrayOf(0,1,2,3, 4,5,6,7, 8,9,10,11)
        check("B. row-padded Y plane (slow path skips padding)", dest, expected)
        if (dest.any { it == (-1).toByte() }) {
            println("FAIL: B. padding byte (0xFF) leaked into output — stride not respected")
            failures++
        }
    }

    // ── Case C: interleaved chroma plane, pixelStride=2 (the actual bug case) ──
    // This is the exact real-world layout the Pass 3 report describes: U and V
    // are both views over ONE shared interleaved buffer VUVUVU..., each with
    // pixelStride=2, offset by 1 byte from each other. NV21 wants V,U,V,U...
    // which, for this layout, is simply the buffer as-is — but only if pixelStride
    // is correctly walked; the pre-fix code (naive concatenation of
    // buffer.remaining() from each plane) would instead emit two full,
    // non-interleaved half-buffers with garbage — the exact bug this fix
    // addresses.
    run {
        val width = 4; val height = 4 // chromaWidth=2, chromaHeight=2
        // Shared interleaved buffer layout: V0 U0 V1 U1 (row0), V2 U2 V3 U3 (row1)
        // — this is the real Android layout: U and V planes are both views over
        // ONE underlying interleaved memory region, each with pixelStride=2,
        // offset from each other by 1 byte. Modeled here with a real
        // ByteBuffer.slice(), which is exactly how Android's Plane.buffer for
        // the U plane is positioned relative to the V plane's in this layout.
        val shared = byteArrayOf(
            10, 20, 11, 21, // row 0: V0=10 U0=20 V1=11 U1=21
            12, 22, 13, 23  // row 1: V2=12 U2=22 V3=13 U3=23
        )
        val vBuf = ByteBuffer.wrap(shared)                 // index 0 == shared[0] (V starts at 0)
        val uBase = ByteBuffer.wrap(shared); uBase.position(1)
        val uBuf = uBase.slice()                           // index 0 == shared[1] (U starts at 1)
        val rowStride = 4; val pixelStride = 2
        val nv21 = ByteArray(8)
        interleaveChroma(vBuf, rowStride, pixelStride, uBuf, rowStride, pixelStride, width, height, nv21, 0)
        val expected = byteArrayOf(10, 20, 11, 21, 12, 22, 13, 23)
        check("C. pixelStride=2 interleaved chroma (V/U as byte-shifted views of one buffer) via real interleaveChroma()", nv21, expected)
    }

    // ── Case D: chroma plane with row padding (rowStride > chromaWidth*pixelStride) ──
    run {
        val width = 4; val height = 4 // chromaWidth=2, chromaHeight=2
        val vRowStride = 8; val pixelStride = 1 // 2 real bytes + padding per row
        val vSrc = byteArrayOf(
            100,101, -1,-1,-1,-1,-1,-1,   // row0: V0=100 V1=101, then padding
            102,103, -1,-1,-1,-1,-1,-1    // row1: V2=102 V3=103, then padding
        )
        val uRowStride = 8
        val uSrc = byteArrayOf(
            50,51, -1,-1,-1,-1,-1,-1,
            52,53, -1,-1,-1,-1,-1,-1
        )
        val vBuf = ByteBuffer.wrap(vSrc)
        val uBuf = ByteBuffer.wrap(uSrc)
        val nv21 = ByteArray(8)
        interleaveChroma(vBuf, vRowStride, pixelStride, uBuf, uRowStride, pixelStride, width, height, nv21, 0)
        val expected = byteArrayOf(100,50, 101,51, 102,52, 103,53)
        check("D. chroma row padding is skipped correctly", nv21, expected)
        if (nv21.any { it == (-1).toByte() }) {
            println("FAIL: D. chroma padding byte leaked into output")
            failures++
        }
    }

    // ── Case E: non-multiple-of-4, larger, asymmetric dimensions ────────────
    run {
        val width = 6; val height = 4 // chromaWidth=3, chromaHeight=2
        // Tightly packed Y (sanity check the fast path at a different size)
        val ySrc = ByteArray(width * height) { it.toByte() }
        val yBuf = ByteBuffer.wrap(ySrc)
        val yDest = ByteArray(width * height)
        copyPlaneRespectingStride(yBuf, width, 1, width, height, yDest, 0)
        check("E1. Y plane fast path at 6x4", yDest, ySrc)

        // Chroma: tightly packed but NOT via the fast path (interleaveChroma has
        // no fast path at all — every call walks index math) — verify general
        // dimension arithmetic (width/2=3, height/2=2) is correct.
        val vSrc = byteArrayOf(1,2,3, 4,5,6) // 3 wide, 2 tall, rowStride=3, pixelStride=1
        val uSrc = byteArrayOf(11,12,13, 14,15,16)
        val vBuf = ByteBuffer.wrap(vSrc)
        val uBuf = ByteBuffer.wrap(uSrc)
        val nv21 = ByteArray(12)
        interleaveChroma(vBuf, 3, 1, uBuf, 3, 1, width, height, nv21, 0)
        val expected = byteArrayOf(1,11, 2,12, 3,13, 4,14, 5,15, 6,16)
        check("E2. chroma index math at asymmetric 6x4 (chroma 3x2)", nv21, expected)
    }

    println()
    println("== $passes passed, $failures failed ==")
    if (failures > 0) kotlin.system.exitProcess(1)
}
