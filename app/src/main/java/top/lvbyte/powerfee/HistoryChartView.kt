package top.lvbyte.powerfee

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 最近 7 天余额曲线。
 *
 * 刻意用自定义 View 手绘，而不是引入图表库：一是这个项目坚持零第三方依赖
 * （APK 只有 1.7 MB），二是要画的东西很简单——一条折线 + 两条阈值虚线。
 *
 * 两条虚线是重点：把"提醒阈值"和"预警线"画在图上，一眼就能看出余额还有多少余量、
 * 什么时候会跌破红线。
 */
class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<Pair<Long, Double>> = emptyList()
    private var threshold = 20.0
    private var warnRatio = 2.0
    private var emptyText = "数据不足，至少需要 2 次采样"

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(10f) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f) }

    private val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun setData(history: List<Pair<Long, Double>>, thresholdValue: Double, warnRatioValue: Double) {
        points = history
        threshold = thresholdValue
        warnRatio = warnRatioValue
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = resolveSize(dp(150f).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = dp(42f)
        val padRight = dp(10f)
        val padTop = dp(10f)
        val padBottom = dp(22f)
        val plotW = w - padLeft - padRight
        val plotH = h - padTop - padBottom
        if (plotW <= 1f || plotH <= 1f) return

        if (points.size < 2) {
            emptyPaint.color = context.getColor(R.color.text_muted)
            canvas.drawText(emptyText, padLeft, h / 2f, emptyPaint)
            return
        }

        // 纵轴范围：把两条阈值线也算进来，避免虚线跑到图外
        var minValue = Double.MAX_VALUE
        var maxValue = -Double.MAX_VALUE
        for (point in points) {
            minValue = min(minValue, point.second)
            maxValue = max(maxValue, point.second)
        }
        minValue = min(minValue, threshold)
        maxValue = max(maxValue, threshold * warnRatio)
        if (maxValue - minValue < 1.0) maxValue = minValue + 1.0
        val margin = (maxValue - minValue) * 0.12
        minValue = max(0.0, minValue - margin)
        maxValue += margin

        val t0 = points.first().first
        val t1 = points.last().first
        val span = max(1L, t1 - t0)
        val range = maxValue - minValue

        fun xOf(t: Long) = padLeft + (t - t0).toFloat() / span.toFloat() * plotW
        fun yOf(v: Double) = padTop + (1.0 - (v - minValue) / range).toFloat() * plotH

        // 坐标轴
        axisPaint.color = context.getColor(R.color.chart_axis)
        canvas.drawLine(padLeft, padTop, padLeft, padTop + plotH, axisPaint)
        canvas.drawLine(padLeft, padTop + plotH, padLeft + plotW, padTop + plotH, axisPaint)

        // 两条阈值虚线：红=低于它就该充值，黄=进入预警区
        val yThreshold = yOf(threshold)
        dashPaint.color = context.getColor(R.color.level_low)
        canvas.drawLine(padLeft, yThreshold, padLeft + plotW, yThreshold, dashPaint)
        val yWarn = yOf(threshold * warnRatio)
        dashPaint.color = context.getColor(R.color.level_warn)
        canvas.drawLine(padLeft, yWarn, padLeft + plotW, yWarn, dashPaint)

        // 折线 + 面积
        val line = Path()
        val area = Path()
        points.forEachIndexed { index, point ->
            val px = xOf(point.first)
            val py = yOf(point.second)
            if (index == 0) {
                line.moveTo(px, py)
                area.moveTo(px, padTop + plotH)
                area.lineTo(px, py)
            } else {
                line.lineTo(px, py)
                area.lineTo(px, py)
            }
        }
        area.lineTo(xOf(t1), padTop + plotH)
        area.close()

        areaPaint.color = context.getColor(R.color.chart_area)
        canvas.drawPath(area, areaPaint)
        linePaint.color = context.getColor(R.color.level_ok)
        canvas.drawPath(line, linePaint)

        // 最后一个点画个实心圆，表示"当前"
        dotPaint.color = context.getColor(R.color.level_ok)
        canvas.drawCircle(xOf(t1), yOf(points.last().second), dp(3.5f), dotPaint)

        // 标注：纵轴上下限 + 阈值线数值 + 起止日期
        textPaint.color = context.getColor(R.color.text_muted)
        canvas.drawText(fmt(maxValue), dp(2f), padTop + sp(10f), textPaint)
        canvas.drawText(fmt(minValue), dp(2f), padTop + plotH, textPaint)
        canvas.drawText(fmt(threshold), dp(2f), yThreshold + sp(3.5f), textPaint)

        canvas.drawText(dayFormat.format(Date(t0 * 1000)), padLeft, h - dp(6f), textPaint)
        val endLabel = dayFormat.format(Date(t1 * 1000))
        canvas.drawText(endLabel, padLeft + plotW - textPaint.measureText(endLabel), h - dp(6f), textPaint)
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * scaledDensity
}
