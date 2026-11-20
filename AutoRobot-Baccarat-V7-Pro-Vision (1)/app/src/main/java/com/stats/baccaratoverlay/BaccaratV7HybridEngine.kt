package com.stats.baccaratoverlay

import kotlin.math.roundToInt

data class DerivedRoadSummary(
    val redCount: Int,
    val blueCount: Int,
    val statusText: String
)

data class V7HybridReport(
    val lastCapturedLabel: String,
    val patternName: String,
    val recommendedSide: String,
    val confidence: Int,
    val probB: Int,
    val probP: Int,
    val robotCallout: String,
    val mainRoadStatusText: String,
    val derivedRoadStatusText: String
)

object BaccaratV7HybridEngine {

    fun evaluate(fullHistory: List<Char>): V7HybridReport {
        if (fullHistory.isEmpty()) {
            return V7HybridReport(
                lastCapturedLabel = "CHƯA CÓ",
                patternName = "CHỜ DỮ LIỆU",
                recommendedSide = "WAIT",
                confidence = 50,
                probB = 50,
                probP = 50,
                robotCallout = "Căn khung vàng khớp bảng cầu rồi bấm BẮT ĐẦU!",
                mainRoadStatusText = "Thế cầu: Đang chờ nhận diện hạt cầu...",
                derivedRoadStatusText = "Cầu phụ: Chưa đủ cột"
            )
        }

        val lastRaw = fullHistory.last()
        val lastCapturedLabel = when (lastRaw) {
            'B' -> "BANKER (CÁI)"
            'P' -> "PLAYER (CON)"
            'T' -> "TIE (HÒA)"
            else -> "KHÔNG RÕ"
        }

        val pb = fullHistory.filter { it == 'B' || it == 'P' }
        val bCount = fullHistory.count { it == 'B' }
        val pCount = fullHistory.count { it == 'P' }
        val tCount = fullHistory.count { it == 'T' }

        if (pb.size < 3) {
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "KHỞI TẠO (${pb.size}/3)",
                recommendedSide = "WAIT",
                confidence = 52,
                probB = 50,
                probP = 50,
                robotCallout = "Đã bắt ${pb.size} tay ($bCount Cái - $pCount Con). Chờ thêm ${3 - pb.size} tay!",
                mainRoadStatusText = "Thế cầu: Đang lấy mẫu (${pb.size} tay B/P, $tCount Hòa)",
                derivedRoadStatusText = "Cầu phụ: Cần tối thiểu 3 tay"
            )
        }

        val derived = computeDerivedRoadConsensus(pb)
        val n = pb.size
        val last = pb[n - 1]
        val oppositeOfLast = if (last == 'B') 'P' else 'B'

        var streakLen = 1
        for (i in n - 2 downTo 0) {
            if (pb[i] == last) streakLen++ else break
        }

        var chopLen = 1
        for (i in n - 1 downTo 1) {
            if (pb[i] != pb[i - 1]) chopLen++ else break
        }

        val twoTwo = detectTwoTwoPattern(pb)
        val oneTwo = detectOneTwoCycle(pb)

        if (chopLen >= 4) {
            val nextSide = if (oppositeOfLast == 'B') "BANKER" else "PLAYER"
            val conf = (75 + (chopLen - 4) * 4 + if (derived.blueCount >= derived.redCount) 4 else 0).coerceIn(74, 95)
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 1-1 ($chopLen nhịp)",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = 100 - probB,
                robotCallout = "⚡ BẮT CẦU NHẢY 1-1 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Nhảy 1-1 liên tục $chopLen nhịp (Đảo chiều)",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (streakLen >= 3) {
            val nextSide = if (last == 'B') "BANKER" else "PLAYER"
            val conf = (73 + (streakLen - 3) * 4 + if (derived.redCount >= derived.blueCount) 5 else 0).coerceIn(72, 94)
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU BỆT ($streakLen tay)",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = 100 - probB,
                robotCallout = "🔥 THEO CẦU BỆT ($streakLen tay) → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Bệt ${if (last == 'B') "NHÀ CÁI" else "TAY CON"} $streakLen tay liên tiếp",
                derivedRoadStatusText = derived.statusText
            )
        }

        if (twoTwo != null) {
            val (nextChar, desc) = twoTwo
            val nextSide = if (nextChar == 'B') "BANKER" else "PLAYER"
            val conf = 79
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 2-2",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = 100 - probB,
                robotCallout = "🎯 BẮT CẦU NHẢY 2-2 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = desc,
                derivedRoadStatusText = derived.statusText
            )
        }

        if (oneTwo != null) {
            val (nextChar, desc) = oneTwo
            val nextSide = if (nextChar == 'B') "BANKER" else "PLAYER"
            val conf = 74
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CẦU NHẢY 1-2 / 2-1",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = 100 - probB,
                robotCallout = "🔄 NHỊP CẦU 1-2 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = desc,
                derivedRoadStatusText = derived.statusText
            )
        }

        if (chopLen == 3) {
            val nextSide = if (oppositeOfLast == 'B') "BANKER" else "PLAYER"
            val conf = 72
            val probB = if (nextSide == "BANKER") conf else (100 - conf)
            return V7HybridReport(
                lastCapturedLabel = lastCapturedLabel,
                patternName = "CHỚM NHẢY 1-1",
                recommendedSide = nextSide,
                confidence = conf,
                probB = probB,
                probP = 100 - probB,
                robotCallout = "⚡ CHỚM CẦU NHẢY 1-1 → VÀO $nextSide ($conf%)",
                mainRoadStatusText = "Đang đảo chiều 1-1 (3 nhịp gần nhất)",
                derivedRoadStatusText = derived.statusText
            )
        }

        val window = pb.takeLast(10)
        val winB = window.count { it == 'B' }
        val winP = window.count { it == 'P' }
        val nextSide = when {
            winB > winP -> "BANKER"
            winP > winB -> "PLAYER"
            else -> if (oppositeOfLast == 'B') "BANKER" else "PLAYER"
        }
        val rawRatio = if (nextSide == "BANKER") (winB.toDouble() / window.size) else (winP.toDouble() / window.size)
        val conf = (60 + (rawRatio * 18).roundToInt()).coerceIn(60, 75)
        val probB = if (nextSide == "BANKER") conf else (100 - conf)

        return V7HybridReport(
            lastCapturedLabel = lastCapturedLabel,
            patternName = "CẦU BIẾN THIÊN ($bCount B - $pCount P)",
            recommendedSide = nextSide,
            confidence = conf,
            probB = probB,
            probP = 100 - probB,
            robotCallout = "📊 ƯU TIÊN $nextSide ($conf%) | Cái:$bCount Con:$pCount",
            mainRoadStatusText = "Nghiêng $nextSide (10 tay: $winB B - $winP P)",
            derivedRoadStatusText = derived.statusText
        )
    }

    private fun detectTwoTwoPattern(pb: List<Char>): Pair<Char, String>? {
        if (pb.size < 5) return null
        val tail5 = pb.takeLast(5).joinToString("")
        if (tail5 == "BBPPB") return 'B' to "Nhịp 2-2 (BB-PP-B) → Đủ cặp BANKER"
        if (tail5 == "PPBBP") return 'P' to "Nhịp 2-2 (PP-BB-P) → Đủ cặp PLAYER"
        if (pb.size >= 6) {
            val tail6 = pb.takeLast(6).joinToString("")
            if (tail6 == "BBPPBB") return 'P' to "Nhịp 2-2 (BB-PP-BB) → Đảo PLAYER"
            if (tail6 == "PPBBPP") return 'B' to "Nhịp 2-2 (PP-BB-PP) → Đảo BANKER"
        }
        return null
    }

    private fun detectOneTwoCycle(pb: List<Char>): Pair<Char, String>? {
        if (pb.size < 5) return null
        val tail5 = pb.takeLast(5).joinToString("")
        if (tail5 == "PBBPB") return 'B' to "Nhịp 1-2 (P-BB-P-B) → Tiếp BANKER"
        if (tail5 == "BPPBP") return 'P' to "Nhịp 1-2 (B-PP-B-P) → Tiếp PLAYER"
        if (pb.size >= 6) {
            val tail6 = pb.takeLast(6).joinToString("")
            if (tail6 == "PBBPBB") return 'P' to "Nhịp 1-2 chuẩn (P-BB-P-BB) → Đảo PLAYER"
            if (tail6 == "BPPBPP") return 'B' to "Nhịp 1-2 chuẩn (B-PP-B-PP) → Đảo BANKER"
        }
        return null
    }

    private fun computeDerivedRoadConsensus(pb: List<Char>): DerivedRoadSummary {
        val columns = mutableListOf<Int>()
        var currentRun = 1
        for (i in 1 until pb.size) {
            if (pb[i] == pb[i - 1]) currentRun++ else {
                columns.add(currentRun)
                currentRun = 1
            }
        }
        columns.add(currentRun)

        if (columns.size < 3) {
            return DerivedRoadSummary(1, 1, "Cầu phụ: Đang hình thành")
        }

        var red = 0
        var blue = 0
        for (c in 1 until columns.size) {
            if (columns[c] == columns[c - 1]) red++ else blue++
        }
        if (columns.size >= 4) {
            for (c in 2 until columns.size) {
                if (columns[c] == columns[c - 2]) red++ else blue++
            }
        }

        val status = when {
            red > blue -> "Cầu phụ: ĐỎ ($red/$blue)"
            blue > red -> "Cầu phụ: XANH ($blue/$red)"
            else -> "Cầu phụ: Cân bằng ($red-$blue)"
        }
        return DerivedRoadSummary(red, blue, status)
    }
}
