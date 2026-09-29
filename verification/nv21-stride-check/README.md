# NV21 Stride Verification

`Nv21StrideTest.kt` is a standalone JVM harness for the stride-aware byte-copy logic used by the child app's camera stream implementation. It uses synthetic `ByteBuffer` data to cover packed and padded rows, pixel-stride-2 interleaved chroma, and asymmetric image dimensions.

This check validates byte-index arithmetic only. It does not exercise Android `ImageProxy`, camera hardware, image conversion, or JPEG encoding; verify those behaviors on supported devices as well.

## Run

Use a Kotlin 1.9.x compiler and JDK 17 or newer. From this directory:

```powershell
kotlinc Nv21StrideTest.kt -include-runtime -d nv21test.jar
java -jar nv21test.jar
```

Expected summary: `== 6 passed, 0 failed ==`.
