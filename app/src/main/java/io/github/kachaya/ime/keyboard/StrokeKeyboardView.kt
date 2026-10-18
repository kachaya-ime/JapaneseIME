package io.github.kachaya.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.github.kachaya.ime.R
import kotlin.math.sqrt

/**
 * 手書きジェスチャ（ストローク）による入力を管理するキーボードビューです。
 */
class StrokeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    fun interface OnHelpListener {
        fun onHelpTriggered()
    }

    private var alphabetStroke: Stroke? = null
    private var numericStroke: Stroke? = null
    private var punctuationStroke: Stroke? = null

    private var mPunctuationFlag: Boolean = false
    private var mShiftSingleFlag: Boolean = false
    private var mShiftLockFlag: Boolean = false
    private var mCtrlSingleFlag: Boolean = false
    private var mModeIconDrawable: Drawable? = null

    private var mStrokePath: Path = Path()
    private var mPaint: Paint = Paint()

    private var mLastX: Float = 0f
    private var mLastY: Float = 0f
    private var mTouchTolerance: Float = 0f

    private var mHelpTriggered: Boolean = false

    private var mShiftSingleDrawable: Drawable? = null
    private var mShiftLockDrawable: Drawable? = null
    private var mCtrlSingleDrawable: Drawable? = null

    private var mHelpListener: OnHelpListener? = null

    fun setOnHelpListener(listener: OnHelpListener?) {
        mHelpListener = listener
    }

    init {
        initView(context)
    }

    private fun initView(context: Context) {
        setWillNotDraw(false)
        readPrefs()

        alphabetStroke = Stroke(Stroke.ALPHABET_DIC)
        numericStroke = Stroke(Stroke.NUMERIC_DIC)
        punctuationStroke = Stroke(Stroke.PUNCTUATION_DIC)

        val res = context.resources

        mShiftSingleDrawable = ResourcesCompat.getDrawable(res, R.drawable.ic_shift_single, null)
        mShiftLockDrawable = ResourcesCompat.getDrawable(res, R.drawable.ic_shift_lock, null)
        mCtrlSingleDrawable = ResourcesCompat.getDrawable(res, R.drawable.ic_ctrl_single, null)

        mStrokePath = Path()

        mPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            color = ResourcesCompat.getColor(res, R.color.stroke_line_color, context.theme)
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 3f
        }
    }

    override fun updateState(state: KeyboardState) {
        mShiftSingleFlag = state.shifted
        mShiftLockFlag = state.shiftLocked
        mCtrlSingleFlag = state.control
        mModeIconDrawable = if (state.modeIconResId != 0) {
            ResourcesCompat.getDrawable(resources, state.modeIconResId, null)
        } else {
            null
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            return
        }

        val xdpi = context.resources.displayMetrics.xdpi
        mTouchTolerance = xdpi / 25.4f

        alphabetStroke = Stroke(Stroke.ALPHABET_DIC)
        numericStroke = Stroke(Stroke.NUMERIC_DIC)
        punctuationStroke = Stroke(Stroke.PUNCTUATION_DIC)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mHelpTriggered = false
                mStrokePath.reset()
                mStrokePath.moveTo(x, y)
                mLastX = x
                mLastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - mLastX
                val dy = y - mLastY

                if (!mHelpTriggered && y < -mTouchTolerance * 2) {
                    mHelpTriggered = true
                    mHelpListener?.onHelpTriggered()
                    mStrokePath.reset()
                    invalidate()
                    return true
                }

                if (mHelpTriggered) {
                    return true
                }

                val d = sqrt((dx * dx + dy * dy).toDouble())
                if (d >= mTouchTolerance) {
                    mStrokePath.quadTo(mLastX, mLastY, (x + mLastX) / 2, (y + mLastY) / 2)
                    mLastX = x
                    mLastY = y
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (mHelpTriggered) {
                    mHelpTriggered = false
                    return true
                }
                mStrokePath.lineTo(mLastX, mLastY)

                val bounds = RectF()
                mStrokePath.computeBounds(bounds, true)
                val isTap = bounds.width() < mTouchTolerance && bounds.height() < mTouchTolerance
                if (isTap) {
                    if (mPunctuationFlag) {
                        mPunctuationFlag = false
                        processCode('.'.code)
                    } else {
                        mPunctuationFlag = true
                    }
                } else {
                    val engine = when {
                        mPunctuationFlag -> punctuationStroke
                        bounds.centerX() > (width / 2.0f) -> numericStroke
                        else -> alphabetStroke
                    }

                    val code = engine?.recognize(mStrokePath) ?: 0
                    if (code != 0) {
                        processCode(code)
                        if (mPunctuationFlag) {
                            mPunctuationFlag = false
                        }
                    }
                }
                mStrokePath.reset()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun processCode(code: Int) {
        val listener = mListener ?: return
        if (code == 0) return

        if (mPunctuationFlag && (code == KeyConfig.CODE_BACKSPACE || code == '.'.code)) {
            mPunctuationFlag = false
            if (code == KeyConfig.CODE_BACKSPACE) return
        }

        val config = if (KeyConfig.codeToString(code) != null) {
            KeyConfig(code)
        } else {
            KeyConfig(code.toChar().toString())
        }
        listener.onKey(config)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        var r = h / 100f

        mPaint.strokeWidth = r / 2
        mPaint.style = Paint.Style.STROKE
        canvas.drawRect(r / 2.0f, r / 2.0f, w - (r / 2.0f), h - (r / 2.0f), mPaint)
        canvas.drawLine(w / 2.0f, r / 2.0f, w / 2.0f, h - (r / 2.0f), mPaint)

        mPaint.style = Paint.Style.FILL_AND_STROKE
        mPaint.textSize = h * 0.1f
        mPaint.textAlign = Paint.Align.CENTER
        val ofsX = h * 0.07f
        val ofsY = h * 0.08f + ((mPaint.descent() + mPaint.ascent()) / 2)
        canvas.drawText("a", ofsX, h - ofsY, mPaint)
        canvas.drawText("1", w - ofsX, h - ofsY, mPaint)

        mPaint.style = Paint.Style.FILL_AND_STROKE
        val cx = h * 0.1f
        val cy = h * 0.1f
        r = h * 0.05f
        val left = (cx - r).toInt()
        val top = (cy - r).toInt()
        val right = (cx + r).toInt()
        val bottom = (cy + r).toInt()

        val strokeColor = ResourcesCompat.getColor(resources, R.color.stroke_line_color, context.theme)

        if (mCtrlSingleFlag) {
            val d = mCtrlSingleDrawable
            if (d != null) {
                DrawableCompat.setTint(d, strokeColor)
                d.setBounds(left, top, right, bottom)
                d.draw(canvas)
            }
        } else if (mPunctuationFlag) {
            canvas.drawCircle(cx, cy, r, mPaint)
        } else {
            if (mShiftLockFlag) {
                val d = mShiftLockDrawable
                if (d != null) {
                    DrawableCompat.setTint(d, strokeColor)
                    d.setBounds(left, top, right, bottom)
                    d.draw(canvas)
                }
            } else if (mShiftSingleFlag) {
                val d = mShiftSingleDrawable
                if (d != null) {
                    DrawableCompat.setTint(d, strokeColor)
                    d.setBounds(left, top, right, bottom)
                    d.draw(canvas)
                }
            }
        }
        mPaint.style = Paint.Style.STROKE
        canvas.drawPath(mStrokePath, mPaint)

        val icon = mModeIconDrawable
        if (icon != null) {
            DrawableCompat.setTint(icon, strokeColor)
            val size = h * 0.1f
            val margin = h * 0.05f
            val leftMode = (w - margin - size).toInt()
            val topMode = margin.toInt()
            val rightMode = (w - margin).toInt()
            val bottomMode = (margin + size).toInt()
            icon.setBounds(leftMode, topMode, rightMode, bottomMode)
            icon.draw(canvas)
        }
    }
}
