package io.github.kachaya.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.kachaya.ime.R
import java.util.Objects

/**
 * RecyclerView を用いて高速に絵文字グリッドを表示・再利用するためのアダプターです。
 */
class EmojiRecyclerViewAdapter(private val context: Context) :
    RecyclerView.Adapter<EmojiRecyclerViewAdapter.EmojiViewHolder>() {

    fun interface OnEmojiClickListener {
        fun onEmojiClick(item: EmojiParser.EmojiItem)
    }

    private var mItems: List<EmojiParser.EmojiItem> = ArrayList()
    private var mListener: OnEmojiClickListener? = null
    private var mRowHeight: Int = 0

    @SuppressLint("NotifyDataSetChanged")
    fun setRowHeight(height: Int) {
        if (mRowHeight != height) {
            mRowHeight = height
            notifyDataSetChanged()
        }
    }

    fun setItems(items: List<EmojiParser.EmojiItem>?) {
        val newItems = items ?: ArrayList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = mItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return Objects.equals(mItems[oldItemPosition].emoji, newItems[newItemPosition].emoji)
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = mItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return Objects.equals(oldItem.emoji, newItem.emoji) &&
                        Objects.equals(oldItem.description, newItem.description) &&
                        Objects.equals(oldItem.group, newItem.group)
            }
        })
        mItems = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener?) {
        mListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        @SuppressLint("AppCompatCustomView")
        val b = object : Button(ContextThemeWrapper(context, R.style.CharacterButton), null, 0) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val width = measuredWidth
                val targetHeight = if (mRowHeight > 0) mRowHeight else context.resources.getDimension(R.dimen.candidate_height).toInt()
                val height = Math.min(width, targetHeight)
                setMeasuredDimension(width, height)
            }
        }
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        b.isAllCaps = false
        b.setPadding(0, 0, 0, 0)
        b.setBackgroundResource(R.drawable.bg_character_button_selector)

        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, context.resources.displayMetrics).toInt()
        val lp = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(marginPx, marginPx, marginPx, marginPx)
        b.layoutParams = lp

        return EmojiViewHolder(b)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        val item = mItems[position]
        holder.button.text = item.emoji
        holder.button.tag = item
        holder.button.setOnClickListener { v ->
            val tag = v.tag
            if (mListener != null && tag is EmojiParser.EmojiItem) {
                mListener?.onEmojiClick(tag)
            }
        }
    }

    override fun getItemCount(): Int = mItems.size

    class EmojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val button: Button = itemView as Button
    }
}
