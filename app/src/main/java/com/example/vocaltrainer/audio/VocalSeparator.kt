package com.example.vocaltrainer.audio

import android.content.Context
import com.example.vocaltrainer.audio.fft.Stft
import com.example.vocaltrainer.log.VocaltrainerLogger
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Getrennte Stereo-Stems: [vocal] = geschätzter Gesangsanteil, [instrumental] = der Rest. */
class SeparatedStems(val vocal: PcmAudio, val instrumental: PcmAudio)

/**
 * On-Device-KI-Stimmtrennung via MDX-Net (TFLite/LiteRT-Export), ersetzt die frühere
 * Mitte-Kanal-Näherung (`VocalBandFilter`, entfernt). Das Modell sagt den Gesangsanteil
 * eines Stereo-Mixes voraus; das Instrumental ergibt sich als Residuum (Original − Gesang) —
 * dadurch ist garantiert, dass Gesang + Instrumental exakt wieder das Original ergeben.
 *
 * Modell: `UVR_MDXNET_9482.fp16acc.tflite`, MIT-lizenziert, siehe
 * `app/src/main/assets/models/LICENSE`. Quelle: huggingface.co/gyoom-sa/UVR-MDX-LiteRT
 * (Format-Konvertierung der etablierten UVR-MDX-Net-Gewichte).
 *
 * STFT-Parameter exakt nach Modell-Spezifikation: n_fft=4096, hop=1024, dim_f=2048,
 * periodisches Hann-Fenster, zentriert mit Reflect-Padding, Tensor-Layout NCHW
 * `[1, 4, dim_f, 256]` mit den Ebenen `[L_re, L_im, R_re, R_im]`. Ein Modell-Durchlauf
 * verarbeitet exakt 256 STFT-Frames (~5,9s bei 44,1kHz); längere Tracks werden in
 * aufeinanderfolgenden 256-Frame-Chunks verarbeitet und per Overlap-Add wieder
 * zusammengesetzt (siehe [Stft] — die Korrektheit dieses chunk-weisen Vorgehens ist über
 * `StftTest#chunked-istft-matches-single-pass-istft` lokal verifiziert, da hier keine
 * Möglichkeit besteht, auf einem echten Gerät zu testen).
 */
object VocalSeparator {

    private const val MODEL_ASSET = "models/UVR_MDXNET_9482.fp16acc.tflite"
    private const val N_FFT = 4096
    private const val HOP = 1024
    private const val DIM_F = 2048
    private const val FRAMES_PER_CHUNK = 256
    private const val PLANES = 4

    suspend fun separate(context: Context, pcm: PcmAudio): SeparatedStems = withContext(Dispatchers.Default) {
        require(pcm.channelCount == 2) { "Stimmtrennung benötigt Stereo-Audio" }

        val t0 = System.currentTimeMillis()
        val modelFile = ensureModelExtracted(context)
        // Einen Kern für die UI freilassen: mit allen Kernen für die Inferenz wurde die
        // App während der ~1 Minute dauernden Trennung spürbar unresponsive (Tippen auf
        // Buttons schien "nicht zu funktionieren").
        val inferenceThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        val model = CompiledModel.create(
            modelFile.absolutePath,
            CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(numThreads = inferenceThreads)
            }
        )
        val t1 = System.currentTimeMillis()
        VocaltrainerLogger.i("VocalSeparator", "Modell geladen in ${t1 - t0}ms")

        try {
            val left = FloatArray(pcm.frameCount)
            val right = FloatArray(pcm.frameCount)
            for (i in 0 until pcm.frameCount) {
                left[i] = pcm.samples[i * 2] / 32768f
                right[i] = pcm.samples[i * 2 + 1] / 32768f
            }

            val stft = Stft(N_FFT, HOP, DIM_F)
            val paddedLeft = stft.padReflect(left)
            val paddedRight = stft.padReflect(right)
            val totalFrames = stft.frameCount(pcm.frameCount)
            val paddedLength = stft.paddedLength(pcm.frameCount)

            val vocalAccumL = FloatArray(paddedLength)
            val vocalAccumR = FloatArray(paddedLength)
            val windowSumAccum = FloatArray(paddedLength)

            var start = 0
            var chunkIndex = 0
            val totalChunks = (totalFrames + FRAMES_PER_CHUNK - 1) / FRAMES_PER_CHUNK
            while (start < totalFrames) {
                // Ohne diese Prüfung würde ein abgebrochener Ladevorgang (z.B. weil der
                // Nutzer während der langen Trennung erneut eine Datei ausgewählt hat)
                // trotzdem bis zum letzten Chunk weiterrechnen, statt sofort aufzuhören.
                ensureActive()
                val count = minOf(FRAMES_PER_CHUNK, totalFrames - start)
                val chunkT0 = System.currentTimeMillis()

                val (lRe, lIm) = stft.forward(paddedLeft, start, count)
                val (rRe, rIm) = stft.forward(paddedRight, start, count)
                val packedInput = packNchw(lRe, lIm, rRe, rIm, count)

                val inputs = model.createInputBuffers()
                val outputs = model.createOutputBuffers()
                try {
                    inputs[0].writeFloat(packedInput)
                    model.run(inputs, outputs)
                    val outputFloats = outputs[0].readFloat()
                    val unpacked = unpackNchw(outputFloats, count)
                    stft.accumulateInverse(unpacked.lRe, unpacked.lIm, start, count, vocalAccumL, windowSumAccum)
                    stft.accumulateInverse(unpacked.rRe, unpacked.rIm, start, count, vocalAccumR, windowSumAccum)
                } finally {
                    inputs.forEach { it.close() }
                    outputs.forEach { it.close() }
                }

                chunkIndex++
                VocaltrainerLogger.d(
                    "VocalSeparator",
                    "Chunk $chunkIndex/$totalChunks (${count} Frames) in ${System.currentTimeMillis() - chunkT0}ms"
                )
                start += count
            }

            val padOffset = stft.padOffset()
            val vocalSamples = ShortArray(pcm.samples.size)
            val instrumentalSamples = ShortArray(pcm.samples.size)
            for (i in 0 until pcm.frameCount) {
                val denom = windowSumAccum[padOffset + i].coerceAtLeast(1e-8f)
                val vocalL = (vocalAccumL[padOffset + i] / denom).coerceIn(-1f, 1f)
                val vocalR = (vocalAccumR[padOffset + i] / denom).coerceIn(-1f, 1f)

                val origL = pcm.samples[i * 2].toInt()
                val origR = pcm.samples[i * 2 + 1].toInt()
                val vocalShortL = clampSample(vocalL * 32768f)
                val vocalShortR = clampSample(vocalR * 32768f)
                vocalSamples[i * 2] = vocalShortL
                vocalSamples[i * 2 + 1] = vocalShortR
                instrumentalSamples[i * 2] = clampSample((origL - vocalShortL).toFloat())
                instrumentalSamples[i * 2 + 1] = clampSample((origR - vocalShortR).toFloat())
            }

            VocaltrainerLogger.i(
                "VocalSeparator",
                "Trennung fertig in ${System.currentTimeMillis() - t0}ms gesamt (${pcm.durationMs}ms Audio)"
            )

            SeparatedStems(
                vocal = PcmAudio(vocalSamples, pcm.sampleRate, 2),
                instrumental = PcmAudio(instrumentalSamples, pcm.sampleRate, 2)
            )
        } finally {
            model.close()
        }
    }

    private fun ensureModelExtracted(context: Context): File {
        val target = File(context.filesDir, "models/UVR_MDXNET_9482.fp16acc.tflite")
        if (!target.exists() || target.length() == 0L) {
            target.parentFile?.mkdirs()
            context.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    /** Packt vier [frame][bin]-Arrays in das vom Modell erwartete NCHW-Layout [1,4,dimF,256]. */
    private fun packNchw(lRe: FloatArray, lIm: FloatArray, rRe: FloatArray, rIm: FloatArray, count: Int): FloatArray {
        val packed = FloatArray(PLANES * DIM_F * FRAMES_PER_CHUNK)
        for (bin in 0 until DIM_F) {
            val base0 = (0 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base1 = (1 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base2 = (2 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base3 = (3 * DIM_F + bin) * FRAMES_PER_CHUNK
            for (frame in 0 until count) {
                val srcIdx = frame * DIM_F + bin
                packed[base0 + frame] = lRe[srcIdx]
                packed[base1 + frame] = lIm[srcIdx]
                packed[base2 + frame] = rRe[srcIdx]
                packed[base3 + frame] = rIm[srcIdx]
            }
        }
        return packed
    }

    /** Kehrt [packNchw] um, liefert vier [frame][bin]-Arrays für die ersten [count] Frames. */
    private fun unpackNchw(packed: FloatArray, count: Int): NchwResult {
        val outLRe = FloatArray(count * DIM_F)
        val outLIm = FloatArray(count * DIM_F)
        val outRRe = FloatArray(count * DIM_F)
        val outRIm = FloatArray(count * DIM_F)
        for (bin in 0 until DIM_F) {
            val base0 = (0 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base1 = (1 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base2 = (2 * DIM_F + bin) * FRAMES_PER_CHUNK
            val base3 = (3 * DIM_F + bin) * FRAMES_PER_CHUNK
            for (frame in 0 until count) {
                val dstIdx = frame * DIM_F + bin
                outLRe[dstIdx] = packed[base0 + frame]
                outLIm[dstIdx] = packed[base1 + frame]
                outRRe[dstIdx] = packed[base2 + frame]
                outRIm[dstIdx] = packed[base3 + frame]
            }
        }
        return NchwResult(outLRe, outLIm, outRRe, outRIm)
    }

    private fun clampSample(value: Float): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private class NchwResult(val lRe: FloatArray, val lIm: FloatArray, val rRe: FloatArray, val rIm: FloatArray)
}
