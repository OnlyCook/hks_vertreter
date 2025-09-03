package com.thecooker.vertretungsplaner.ui.grades

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.text.DecimalFormat
import kotlin.math.max
import kotlin.math.min

class GradeGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val goalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gradeHistory: List<GradesFragment.GradeHistoryEntry> = emptyList()
    private var goalGrade: Float? = null
    private val padding = 80f

    init {
        textPaint.apply {
            color = Color.BLACK
            textSize = 28f
        }

        gridPaint.apply {
            color = Color.LTGRAY
            strokeWidth = 2f
        }

        linePaint.apply {
            color = Color.BLUE
            strokeWidth = 6f
            style = Paint.Style.STROKE
        }

        goalLinePaint.apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        paint.apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }
    }

    fun setData(history: List<GradesFragment.GradeHistoryEntry>, goalGrade: Float? = null) {
        this.gradeHistory = history
        this.goalGrade = goalGrade
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (gradeHistory.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val graphWidth = width - 2 * padding
        val graphHeight = height - 2 * padding

        // Draw grid lines for grades (1-6)
        for (i in 1..6) {
            val y = padding + (graphHeight * (i - 1) / 5)
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
            canvas.drawText(i.toString(), 20f, y + 10f, textPaint)
        }

        // Draw goal grade line if set
        goalGrade?.let { goal ->
            if (goal >= 1f && goal <= 6f) {
                val normalizedGoal = (goal - 1) / 5
                val goalY = padding + (graphHeight * normalizedGoal)
                canvas.drawLine(padding, goalY, width - padding, goalY, goalLinePaint)

                // Draw goal grade label
                val goalText = "Ziel: ${DecimalFormat("0.0").format(goal)}"
                textPaint.color = Color.RED
                canvas.drawText(goalText, width - padding - 80f, goalY - 10f, textPaint)
                textPaint.color = Color.BLACK
            }
        }

        // Draw data points and lines
        val path = Path()
        val points = mutableListOf<PointF>()

        gradeHistory.forEachIndexed { index, entry ->
            val x = padding + (graphWidth * index / max(1, gradeHistory.size - 1))
            val normalizedGrade = ((entry.grade - 1) / 5).toFloat()
            val y = padding + (graphHeight * normalizedGrade)

            points.add(PointF(x, y))

            // Draw data point
            canvas.drawCircle(x, y, 8f, paint)

            // Draw grade value
            val gradeText = DecimalFormat("0.0").format(entry.grade)
            canvas.drawText(gradeText, x - 15f, y - 15f, textPaint)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw the line connecting points
        canvas.drawPath(path, linePaint)

        // Draw month labels with intelligent spacing
        drawMonthLabels(canvas, width, height, graphWidth)
    }

    private fun drawMonthLabels(canvas: Canvas, width: Float, height: Float, graphWidth: Float) {
        if (gradeHistory.isEmpty()) return

        val labelsToShow = getLabelsToShow()

        labelsToShow.forEach { (originalIndex, entry) ->
            // Calculate x position based on original index to maintain accurate spacing
            val x = padding + (graphWidth * originalIndex / max(1, gradeHistory.size - 1))
            val monthName = getMonthName(entry.month).take(3)
            val yearText = "${monthName}\n${entry.year}"

            textPaint.textSize = 34f
            val lines = yearText.split("\n")
            lines.forEachIndexed { lineIndex, line ->
                canvas.drawText(
                    line,
                    x - 30f,
                    height - padding + 40f + (lineIndex * 35f),
                    textPaint
                )
            }
            textPaint.textSize = 38f
        }
    }

    private fun getLabelsToShow(): List<Pair<Int, GradesFragment.GradeHistoryEntry>> {
        val maxLabels = 6
        val totalEntries = gradeHistory.size

        if (totalEntries <= maxLabels) {
            // Show all entries
            return gradeHistory.mapIndexed { index, entry -> index to entry }
        }

        // Create a list of indices to show
        val indicesToShow = mutableListOf<Int>()

        // Always show first and last
        indicesToShow.add(0)
        if (totalEntries > 1) {
            indicesToShow.add(totalEntries - 1)
        }

        // Calculate how many more we can add (we already have first and last)
        val remainingSlots = maxLabels - indicesToShow.size

        if (remainingSlots > 0 && totalEntries > 2) {
            // Distribute the remaining slots evenly across the middle entries
            val step = (totalEntries - 1).toFloat() / (maxLabels - 1)

            for (i in 1 until maxLabels - 1) {
                val index = (step * i).toInt()
                if (index > 0 && index < totalEntries - 1 && !indicesToShow.contains(index)) {
                    indicesToShow.add(index)
                }
            }
        }

        // Sort indices and return corresponding entries
        return indicesToShow.sorted().map { index ->
            index to gradeHistory[index]
        }
    }

    private fun getMonthName(month: Int): String {
        return when (month) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mär"
            4 -> "Apr"
            5 -> "Mai"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Okt"
            11 -> "Nov"
            12 -> "Dez"
            else -> "M$month"
        }
    }
}