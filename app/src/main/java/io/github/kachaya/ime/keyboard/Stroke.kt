package io.github.kachaya.ime.keyboard

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import androidx.core.graphics.PathParser
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手書きのジェスチャ（Path）を解析し、登録された辞書から最適な文字コードを特定する認識エンジンクラスです。
 */
class Stroke(private val dictionary: List<DictionaryEntry>) {

    companion object {
        const val DIRECTION_COUNT = 16
        const val DEFAULT_SAMPLING_COUNT = 32

        @JvmField
        val ALPHABET_DIC: MutableList<DictionaryEntry> = ArrayList()

        @JvmField
        val NUMERIC_DIC: MutableList<DictionaryEntry> = ArrayList()

        @JvmField
        val PUNCTUATION_DIC: MutableList<DictionaryEntry> = ArrayList()

        private class RawEntry(
            val a: Int,
            val n: Int,
            val p: Int,
            val svg: String
        )

        init {
            val source = arrayOf(
                RawEntry('i'.code, '1'.code, '\''.code, "m 6,1 v 10"),
                RawEntry(KeyConfig.CODE_SHIFT, 0, '!'.code, "m 6,11 v -10"),
                RawEntry(KeyConfig.CODE_SPACE, KeyConfig.CODE_SPACE, '-'.code, "m 1,6 h 10"),
                RawEntry(KeyConfig.CODE_BACKSPACE, KeyConfig.CODE_BACKSPACE, KeyConfig.CODE_BACKSPACE, "m 11,6 h -10"),
                RawEntry(KeyConfig.CODE_ENTER, KeyConfig.CODE_ENTER, ','.code, "m 10,2 -8,8"),
                RawEntry(KeyConfig.CODE_CTRL, KeyConfig.CODE_CTRL, '/'.code, "m 2,10 8,-8"),
                RawEntry(KeyConfig.CODE_SYM, KeyConfig.CODE_SYM, '\\'.code, "m 2,2 8,8"),
                RawEntry(KeyConfig.CODE_CANCEL, KeyConfig.CODE_CANCEL, KeyConfig.CODE_CANCEL, "m 10,10 -8,-8"),
                RawEntry(KeyConfig.CODE_DOWN, KeyConfig.CODE_DOWN, ':'.code, "m 6,1 v 10 -10"),
                RawEntry(KeyConfig.CODE_UP, KeyConfig.CODE_UP, '|'.code, "m 6,11 v -10 10"),
                RawEntry(KeyConfig.CODE_RIGHT, KeyConfig.CODE_RIGHT, '_'.code, "m 1,6 h 10 -10"),
                RawEntry(KeyConfig.CODE_LEFT, KeyConfig.CODE_LEFT, 0, "m 11,6 h -10 10"),
                RawEntry(0, '8'.code, ';'.code, "m 10,2 -8,8 8,-8"),
                RawEntry(KeyConfig.CODE_8_2_8, KeyConfig.CODE_8_2_8, KeyConfig.CODE_8_2_8, "m 2,10 8,-8 -8,8"),
                RawEntry(KeyConfig.CODE_10_4_10, KeyConfig.CODE_10_4_10, KeyConfig.CODE_10_4_10, "m 2,2 8,8 -8,-8"),
                RawEntry(0, 0, '`'.code, "m 10,10 -8,-8 8,8"),
                RawEntry(KeyConfig.CODE_SHORTCUT, KeyConfig.CODE_SHORTCUT, 0, "m 3,11 c 0,0 5,-4 5,-7 0,-3 -2,-3 -2,-3 0,0 -2,0 -2,3 0,3 5,7 5,7"),
                RawEntry('a'.code, '1'.code, '^'.code, "m 2,11 c 0,0 3,-10 4,-10 1,0 4,10 4,10"),
                RawEntry('b'.code, 0, 0, "m 3,11 v -7 c 0,0 0,-3 3,-3 3,0 3,2 3,2 0,3 -5,3 -5,3 7,0 6,5 2,5 -1,0 -2,-1 -2,-1"),
                RawEntry('b'.code, 0, 0, "m 3,1 v 8 c 0,0 0,-8 3,-8 3,0 3,2 3,2 0,3 -5,3 -5,3 7,0 6,5 2,5 -2,0 -3,-1 -3,-1"),
                RawEntry('c'.code, 0, '('.code, "m 9,3 c 0,0 -1,-2 -3,-2 -4,0 -4,5 -4,5 0,0 0,5 4,5 2,0 3,-2 3,-2"),
                RawEntry('c'.code, 0, '('.code, "m 8,1 c 0,0 -4,1 -4,5 0,4 4,5 4,5"),
                RawEntry('d'.code, 0, 0, "m 3,1 v 8 c 0,0 0,-8 3,-8 4,0 4,5 4,5 0,0 0,5 -4,5 -2,0 -3,-1 -3,-1"),
                RawEntry('d'.code, 0, 0, "m 3,11 v -8 c 0,0 0,-2 2,-2 5,0 5,5 5,5 0,0 0,5 -6,5"),
                RawEntry('e'.code, 0, '{'.code, "m 8,2 c 0,0 -5,0 -5,4 0,4 5,0 5,0 -5,0 -5,4 0,4"),
                RawEntry('f'.code, 0, 0, "m 9,1 h -4 c -1,0 -2,1 -2,2 v 8"),
                RawEntry('f'.code, 0, KeyConfig.CODE_TAB, "m 3,11 v -8 c 0,-1 1,-2 2,-2 h 4"),
                RawEntry('g'.code, 0, 0, "m 9,2 c 0,0 -1,-1 -3,-1 -4,0 -4,5 -4,5 0,0 0,5 4,5 3,0 3,-3 3,-3 0,-2 -3,-2 -3,-2 h 4"),
                RawEntry('g'.code, '6'.code, 0, "m 8,1 c 0,0 -5,0 -5,6 0,4 3,4 3,4 0,0 3,0 3,-3 0,-2 -2,-2 -2,-2 -2,0 -3,1 -3,1"),
                RawEntry('h'.code, '9'.code, '#'.code, "m 3,1 v 10 c 0,0 0,-6 3,-6 3,0 3,6 3,6"),
                RawEntry('h'.code, '9'.code, '#'.code, "m 2,1 v 9 c 0,1 1,1 2,0 l 4,-8 c 1,-1 2,-1 2,0 v 9"),
                RawEntry('j'.code, 0, ','.code, "m 9,1 v 8 c 0,1 -1,2 -2,2 h -4"),
                RawEntry('k'.code, 0, '+'.code, "m 10,1 c 0,0 -3,7 -6,7 -2,0 -2,-2 -2,-2 0,0 0,-2 2,-2 3,0 6,7 6,7"),
                RawEntry('k'.code, 0, '+'.code, "m 11,3 c 0,0 -4,5 -7,5 -4,0 -4,-4 0,-4 3,0 7,5 7,5"),
                RawEntry('l'.code, '4'.code, '('.code, "m 2,1 v 8 c 0,1 1,2 2,2 h 6"),
                RawEntry('l'.code, '4'.code, '('.code, "m 3,2 v 4 c 0,0 0,2 2,2 h 4"),
                RawEntry('m'.code, 0, 0, "m 2,11 c 0,0 0,-10 2,-10 2,0 2,6 2,6 0,0 0,-6 2,-6 2,0 2,10 2,10"),
                RawEntry('m'.code, 0, 0, "m 2,1 v 10 c 0,0 0,-10 2,-10 2,0 2,6 2,6 0,0 0,-6 2,-6 2,0 2,10 2,10"),
                RawEntry('n'.code, 0, '"'.code, "m 2,11 v -9 c 0,-1 1,-1 2,0 l 4,8 c 1,1 2,1 2,0 l 0,-9"),
                RawEntry('o'.code, '0'.code, '@'.code, "m 5,1 c 0,0 -3,1 -3,5 0,5  4,5  4,5 0,0  4,0  4,-5 0,-4 -3,-5 -3,-5"),
                RawEntry('o'.code, '0'.code, '@'.code, "m 7,1 c 0,0  3,1  3,5 0,5 -4,5 -4,5 0,0 -4,0 -4,-5 0,-4  3,-5  3,-5"),
                RawEntry('p'.code, 0, 0, "m 3,1 v 10 c 0,0 0,-10 3,-10 3,0 3,2 3,2 0,3 -5,3 -5,3"),
                RawEntry('p'.code, 0, 0, "m 3,11 v -7 c 0,0 0,-3 3,-3 3,0 3,2 3,2 0,3 -5,3 -5,3"),
                RawEntry('q'.code, 0, 0, "m 4,2 c 0,0 -2,1 -2,5 0,4 3,4 3,4 0,0 4,0 4,-5 0,-4 -3,-5 -3,-5 h 4"),
                RawEntry('r'.code, 0, 0, "m 3,1 v 10 c 0,0 0,-10 3,-10 3,0 3,2 3,2 0,3 -5,3 -5,3 4,0 5,5 5,5"),
                RawEntry('r'.code, 0, 0, "m 3,11 v -7 c 0,0 0,-3 3,-3 3,0 3,2 3,2 0,3 -5,3 -5,3 4,0 5,5 5,5"),
                RawEntry('s'.code, '5'.code, '$'.code, "m 9,3 c 0,0 -1,-2 -3,-2 0,0 -3,0 -3,3 0,3 6,1 6,4 0,3 -3,3 -3,3 -2,0 -3,-2 -3,-2"),
                RawEntry('s'.code, '5'.code, '$'.code, "m 9,1 c 0,0 -4,0 -5,1 -1,1 -1,4 -1,4 0,0 7,-3 7,2 0,3 -4,3 -4,3 -2,0 -3,-1 -3,-1"),
                RawEntry('t'.code, '7'.code, '?'.code, "m 3,1 h 4 c 2,0 2,1 2,2 v 8"),
                RawEntry('t'.code, '7'.code, '?'.code, "m 3,1 h 5 c 1,0 1,1 0,4 l -2,6"),
                RawEntry('u'.code, '0'.code, 0, "m 2,1 v 6 c 0,0 0,4 4,4 4,0 4,-4 4,-4 v -6"),
                RawEntry('v'.code, '0'.code, 0, "m 10,1 c 0,0 -3,10 -4,10 -1,0 -4,-10 -4,-10"),
                RawEntry('v'.code, 0, 0, "m 2,1 c 0,0 2,10 3,10 1,0 2,-9 2,-9 0,-1 1,-1 2,-1 h 1"),
                RawEntry('w'.code, 0, 0, "m 2,1 c 0,0 0,10 2,10 2,0 2,-6 2,-6 0,0 0,6 2,6 2,0 2,-10 2,-10"),
                RawEntry('x'.code, '6'.code, '*'.code, "m 2,1 c 0,0 4,7 6,7 2,0 2,-2 2,-2 0,0 0,-2 -2,-2 -2,0 -6,7 -6,7"),
                RawEntry('x'.code, '6'.code, '*'.code, "m 1,3 c 0,0 4,5 7,5 4,0 4,-4 0,-4 -3,0 -7,5 -7,5"),
                RawEntry('y'.code, '8'.code, '&'.code, "m 3,1 v 2 c 0,0 0,2 2,2 2,0 3,-4 3,-4 0,0 -1,10 -3,10 -1,0 -2,-1 -1,-3 1,-2 6,-4 6,-4"),
                RawEntry('y'.code, '8'.code, '&'.code, "m 3,1 c 0,0 5,4 5,7 0,3 -2,3 -2,3 0,0 -2,0 -2,-3 0,-3 5,-7 5,-7"),
                RawEntry('z'.code, '2'.code, '='.code, "m 2,1 h 7 c 1,0 1,1 0,2 l -6,6 c -1,1 -1,2 0,2 h 7"),
                RawEntry('z'.code, '2'.code, '='.code, "m 3,4 c 0,0 0,-3 3,-3 3,0 3,3 3,3 0,2 -6,7 -6,7 h 6"),
                RawEntry('b'.code, '3'.code, '}'.code, "m 4,2 c 0,0 1,-1 2,-1 4,0 4,5 0,5 4,0 4,5 0,5 -2,0 -3,-2 -3,-2"),
                RawEntry('s'.code, '5'.code, '$'.code, "m 4,1 -1,5 c 0,0 7,-3 7,2 0,3 -4,3 -4,3 -2,0 -3,-1 -3,-1"),
                RawEntry(0, '8'.code, '&'.code, "m 4,1 c 0,0 5,0 5,2 0,2 -6,2 -6,5 0,3 3,3 3,3 0,0 3,0 3,-3 0,-3 -6,-5 -6,-5"),
                RawEntry(0, '8'.code, '&'.code, "m 9,1 c 0,0 -5,4 -5,7 0,3 2,3 2,3 0,0 2,0 2,-3 0,-3 -5,-7 -5,-7"),
                RawEntry('s'.code, '9'.code, 0, "m 8,1 c 0,0 -5,0 -5,3 0,3 5,2 6,-1 l -2,8"),
                RawEntry(0, 0, '?'.code, "m 3,6 c 0,0 1,-2 3,-2 0,0 3,0 3,2 0,3 -3,3 -3,8"),
                RawEntry(0, 0, ')'.code, "m 4,1 c 0,0 4,1 4,5 0,4 -4,5 -4,5"),
                RawEntry(0, 0, '<'.code, "m 11,10 c 0,0 -10,-3 -10,-4 0,-1 10,-4 10,-4"),
                RawEntry(0, 0, '>'.code, "m 1,10 c 0,0 10,-3 10,-4 0,-1 -10,-4 -10,-4"),
                RawEntry(0, 0, '['.code, "m 9,9 c 0,0 -1,2 -3,2 -4,0 -4,-5 0,-5 -4,0 -4,-5 0,-5 1,0 2,1 2,1"),
                RawEntry(0, 0, ']'.code, "m 3,9 c 0,0 1,2 3,2 4,0 4,-5 0,-5 4,0 4,-5 0,-5 -1,0 -2,1 -2,1"),
                RawEntry(0, 0, '%'.code, "m 1,1 c 0,0 4,4 4,7 0,4 -3,4 -3,0 0,-3 3,-5 4,-5 1,0 4,2 4,5 0,4 -3,4 -3,0 0,-3.5 4,-7 4,-7"),
                RawEntry(0, 0, '~'.code, "m 9,1 v 9 c 0,2 -1,0 -3,-4 -2,-4 -3,-6 -3,-4 v 9")
            )

            for (re in source) {
                try {
                    val path = PathParser.createPathFromPathData(re.svg)
                    val pts = PathSampler.sample(path)
                    if (pts.size >= 2) {
                        val fv = FeatureExtractor.extract(Preprocessor.normalize(pts))
                        if (re.a != 0) ALPHABET_DIC.add(DictionaryEntry(re.a, fv))
                        if (re.n != 0) NUMERIC_DIC.add(DictionaryEntry(re.n, fv))
                        if (re.p != 0) PUNCTUATION_DIC.add(DictionaryEntry(re.p, fv))
                    }
                } catch (_: Exception) {
                }
            }
        }

        private fun dirDist(a: Int, b: Int): Int {
            val d = abs(a - b)
            return min(d, DIRECTION_COUNT - d)
        }
    }

    /**
     * 入力された Path を解析し、最適な文字コードを特定します。
     */
    fun recognize(path: Path): Int {
        val pts = PathSampler.sample(path)
        if (pts.size < 2) {
            return 0
        }

        val norm = Preprocessor.normalize(pts)
        val fv = FeatureExtractor.extract(norm)

        val candidates = ArrayList(dictionary)
        if (candidates.isEmpty()) return 0

        class ScoredEntry(val entry: DictionaryEntry, val score: Double) : Comparable<ScoredEntry> {
            override fun compareTo(other: ScoredEntry): Int {
                return this.score.compareTo(other.score)
            }
        }

        val scoredEntries = ArrayList<ScoredEntry>()
        for (e in candidates) {
            var dirDiffSum = 0.0
            val n = min(fv.dir.size, e.fv.dir.size)
            for (i in 0..<n) {
                val d = dirDist(fv.dir[i], e.fv.dir[i])
                dirDiffSum += d.toDouble()
            }
            val avgDirDiff = if (n > 0) dirDiffSum / n else 0.0
            scoredEntries.add(ScoredEntry(e, avgDirDiff))
        }
        scoredEntries.sort()
        if (scoredEntries.isNotEmpty()) {
            return scoredEntries[0].entry.code
        }

        return 0
    }

    class DictionaryEntry(
        @JvmField val code: Int,
        @JvmField val fv: FeatureVector
    )

    class FeatureVector {
        @JvmField var dir: IntArray = intArrayOf()
        @JvmField var normalizedLength: Double = 0.0
        @JvmField var startDir: Int = 0
        @JvmField var endDir: Int = 0
        @JvmField var startEndDist: Double = 0.0
        @JvmField var travelX: Double = 0.0
        @JvmField var travelY: Double = 0.0
    }

    object Preprocessor {
        @JvmStatic
        fun normalize(pts: List<PointF>): List<PointF> {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            val w = maxX - minX
            val h = maxY - minY
            val scaleX: Float
            val scaleY: Float
            if (w < h / 3.0f) {
                scaleX = 1.0f / max(h, 1e-9f)
                scaleY = scaleX
            } else if (h < w / 3.0f) {
                scaleX = 1.0f / max(w, 1e-9f)
                scaleY = scaleX
            } else {
                scaleX = 1.0f / max(w, 1e-9f)
                scaleY = 1.0f / max(h, 1e-9f)
            }
            val out = ArrayList<PointF>()
            for (p in pts) {
                out.add(PointF((p.x - minX) * scaleX, (p.y - minY) * scaleY))
            }
            return out
        }
    }

    object FeatureExtractor {
        @JvmStatic
        fun extract(pts: List<PointF>): FeatureVector {
            val fv = FeatureVector()
            val n = pts.size - 1
            fv.dir = IntArray(n)
            val ang = DoubleArray(n)
            for (i in 0..<n) {
                ang[i] = atan2((pts[i + 1].y - pts[i].y).toDouble(), (pts[i + 1].x - pts[i].x).toDouble())
                val deg = (Math.toDegrees(ang[i]) + 360) % 360
                val bucketSize = 360.0 / DIRECTION_COUNT
                fv.dir[i] = (((deg + bucketSize / 2.0) % 360) / bucketSize).toInt()
            }
            fv.normalizedLength = 0.0
            fv.travelX = 0.0
            fv.travelY = 0.0
            for (i in 0..<n) {
                val dx = pts[i + 1].x - pts[i].x
                val dy = pts[i + 1].y - pts[i].y
                fv.normalizedLength += hypot(dx.toDouble(), dy.toDouble())
                fv.travelX += abs(dx.toDouble())
                fv.travelY += abs(dy.toDouble())
            }
            fv.startDir = if (fv.dir.isNotEmpty()) fv.dir[0] else 0
            fv.endDir = if (fv.dir.isNotEmpty()) fv.dir[fv.dir.size - 1] else 0
            fv.startEndDist = hypot((pts[0].x - pts[n].x).toDouble(), (pts[0].y - pts[n].y).toDouble())
            return fv
        }
    }

    object PathSampler {
        @JvmStatic
        fun sample(path: Path): List<PointF> {
            val pts = ArrayList<PointF>()
            val pm = PathMeasure(path, false)
            val length = pm.length
            val interval = length / (DEFAULT_SAMPLING_COUNT - 1)
            val pos = FloatArray(2)
            for (i in 0..<DEFAULT_SAMPLING_COUNT) {
                pm.getPosTan(i * interval, pos, null)
                pts.add(PointF(pos[0], pos[1]))
            }
            return pts
        }
    }
}
