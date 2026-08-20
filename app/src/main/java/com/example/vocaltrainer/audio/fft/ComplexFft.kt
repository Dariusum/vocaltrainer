package com.example.vocaltrainer.audio.fft

/**
 * In-place radix-2 Cooley-Tukey FFT für komplexe Signale. Länge muss eine Zweierpotenz sein.
 * [re]/[im] sind separate, gleich lange Arrays und werden in-place überschrieben.
 */
object ComplexFft {

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        require(im.size == n) { "re und im müssen gleich lang sein" }
        require(n > 0 && (n and (n - 1)) == 0) { "Länge muss eine Zweierpotenz sein, war $n" }
        if (n == 1) return

        // Bit-Reversal-Permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }

        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len / 2
            val angleStep = sign * 2.0 * Math.PI / len
            // Vorab berechnete Twiddle-Faktoren für diese Stufe.
            val wr = DoubleArray(half)
            val wi = DoubleArray(half)
            for (k in 0 until half) {
                val angle = angleStep * k
                wr[k] = Math.cos(angle)
                wi[k] = Math.sin(angle)
            }
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val evenIdx = i + k
                    val oddIdx = i + k + half
                    val oddRe = re[oddIdx]
                    val oddIm = im[oddIdx]
                    val tRe = (oddRe * wr[k] - oddIm * wi[k]).toFloat()
                    val tIm = (oddRe * wi[k] + oddIm * wr[k]).toFloat()
                    re[oddIdx] = re[evenIdx] - tRe
                    im[oddIdx] = im[evenIdx] - tIm
                    re[evenIdx] += tRe
                    im[evenIdx] += tIm
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / n
            for (i in 0 until n) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }
}
