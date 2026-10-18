package io.github.kachaya.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import io.github.kachaya.ime.R

/**
 * # group: ごとにカテゴリ分けされた絵文字を表示し、選択して入力できるキーボードビューです。
 */
class EmojiKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : KeyboardView(context, attrs) {

    fun interface OnCloseListener {
        fun onClose()
    }

    private var mCloseListener: OnCloseListener? = null
    private var mEmojiGroups: Map<String, List<EmojiParser.EmojiItem>>
    private var mGroupKeys: MutableList<String>
    private var mCurrentGroup: String? = null
    private val mCategoryBarLayout: LinearLayout
    private val mCategoryScroll: HorizontalScrollView
    private val mViewPager: ViewPager2
    private val mPagerAdapter: EmojiPagerAdapter
    private val mCategoryButtons: MutableList<Button> = ArrayList()
    private var mRowHeight: Int = 0

    init {
        orientation = VERTICAL

        mEmojiGroups = EmojiParser.parse(context)
        mGroupKeys = ArrayList(mEmojiGroups.keys)
        if (mGroupKeys.isNotEmpty()) {
            mCurrentGroup = mGroupKeys[0]
        }

        val topBar = LinearLayout(context)
        topBar.orientation = HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(4, 4, 4, 4)

        mCategoryScroll = HorizontalScrollView(context)
        mCategoryScroll.isHorizontalScrollBarEnabled = false
        mCategoryBarLayout = LinearLayout(context)
        mCategoryBarLayout.orientation = HORIZONTAL
        mCategoryBarLayout.gravity = Gravity.CENTER_VERTICAL
        mCategoryScroll.addView(mCategoryBarLayout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val scrollLp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
        topBar.addView(mCategoryScroll, scrollLp)

        val closeBtn = Button(ContextThemeWrapper(context, R.style.FunctionalKeyButton), null, 0)
        closeBtn.text = "閉じる"
        closeBtn.isAllCaps = false
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        closeBtn.setBackgroundResource(R.drawable.bg_function_button_selector)
        val closePadH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
        closeBtn.setPadding(closePadH, 0, closePadH, 0)

        val closeMarginH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()
        val closeLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        closeLp.setMargins(closeMarginH, 4, closeMarginH, 4)
        closeBtn.layoutParams = closeLp
        closeBtn.setOnClickListener { v ->
            performHapticFeedback(v)
            mCloseListener?.onClose()
        }
        topBar.addView(closeBtn)

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        mViewPager = ViewPager2(context)
        mPagerAdapter = EmojiPagerAdapter()
        mViewPager.adapter = mPagerAdapter

        mViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position in mGroupKeys.indices) {
                    mCurrentGroup = mGroupKeys[position]
                    updateCategorySelection()
                }
            }
        })

        val vpLp = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.0f)
        addView(mViewPager, vpLp)

        initCategories()
    }

    override fun performHapticFeedback(v: View) {
        // 絵文字ピッカーでは振動フィードバックを行わない
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setOnCloseListener(listener: OnCloseListener?) {
        mCloseListener = listener
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun readPrefs() {
        super.readPrefs()
        mEmojiGroups = EmojiParser.parse(context)
        mGroupKeys = ArrayList(mEmojiGroups.keys)
        if (mCurrentGroup == null && mGroupKeys.isNotEmpty()) {
            mCurrentGroup = mGroupKeys[0]
        }
        initCategories()
        mPagerAdapter.notifyDataSetChanged()
        val group = mCurrentGroup
        if (group != null) {
            val index = mGroupKeys.indexOf(group)
            if (index >= 0) {
                mViewPager.setCurrentItem(index, false)
            }
        }
    }

    override fun setRowHeight(height: Int) {
        if (mRowHeight != height) {
            mRowHeight = height
            mPagerAdapter.setRowHeight(height)
        }
    }

    override fun updateState(state: KeyboardState) {
        // 絵文字キーボードは修飾キーの状態に依存しません
    }

    private fun switchToGroup(groupName: String) {
        val index = mGroupKeys.indexOf(groupName)
        if (index in mGroupKeys.indices) {
            mViewPager.setCurrentItem(index, true)
        }
    }

    private fun initCategories() {
        mCategoryBarLayout.removeAllViews()
        mCategoryButtons.clear()
        if (mEmojiGroups.isEmpty()) return

        val ctx = context
        val padH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics).toInt()
        val marginH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics).toInt()

        for (i in mGroupKeys.indices) {
            val groupName = mGroupKeys[i]
            val btn = Button(ContextThemeWrapper(ctx, R.style.CharacterButton), null, 0)
            btn.text = groupName
            btn.isAllCaps = false
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            btn.setBackgroundResource(R.drawable.bg_emoji_category_button)
            btn.setPadding(padH, 0, padH, 0)

            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.setMargins(marginH, 4, marginH, 4)
            btn.layoutParams = lp

            btn.setOnClickListener { v ->
                performHapticFeedback(v)
                switchToGroup(groupName)
            }

            mCategoryBarLayout.addView(btn)
            mCategoryButtons.add(btn)
        }
        updateCategorySelection()
    }

    private fun updateCategorySelection() {
        for (i in mGroupKeys.indices) {
            val groupName = mGroupKeys[i]
            val btn = mCategoryButtons[i]
            val isSelected = groupName == mCurrentGroup
            btn.isSelected = isSelected

            if (isSelected) {
                val selectedBtn = btn
                mCategoryScroll.post { mCategoryScroll.smoothScrollTo(selectedBtn.left - 50, 0) }
            }
        }
    }

    private inner class EmojiPagerAdapter : RecyclerView.Adapter<PageViewHolder>() {

        private var mRowHeight = 0

        @SuppressLint("NotifyDataSetChanged")
        fun setRowHeight(height: Int) {
            if (this.mRowHeight != height) {
                this.mRowHeight = height
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val rv = RecyclerView(parent.context)
            rv.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            rv.layoutManager = GridLayoutManager(parent.context, 10)
            rv.itemAnimator = null
            rv.setItemViewCacheSize(100)
            rv.setHasFixedSize(true)
            rv.clipToPadding = false

            val padBottom = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f, parent.context.resources.displayMetrics
            ).toInt()
            rv.setPadding(0, 0, 0, padBottom)

            val adapter = EmojiRecyclerViewAdapter(parent.context)
            adapter.setRowHeight(mRowHeight)
            adapter.setOnEmojiClickListener { item ->
                mListener?.onKey(KeyConfig(item.emoji))
            }
            rv.adapter = adapter

            return PageViewHolder(rv, adapter)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            if (position in mGroupKeys.indices) {
                val group = mGroupKeys[position]
                val items = mEmojiGroups[group]
                holder.adapter.setRowHeight(mRowHeight)
                holder.adapter.setItems(items)
            }
        }

        override fun getItemCount(): Int = mGroupKeys.size
    }

    private class PageViewHolder(itemView: View, val adapter: EmojiRecyclerViewAdapter) :
        RecyclerView.ViewHolder(itemView)
}
