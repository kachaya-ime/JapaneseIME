package io.github.kachaya.ime.engine

import android.os.SystemClock
import android.view.KeyEvent
import io.github.kachaya.ime.InputService
import io.github.kachaya.ime.dictionary.Candidate

/**
 * IME エンジン（InputEngine）と UI 表現層（InputService / InputView / テスト環境）を仲介するブリッジ・インターフェースです。
 * これにより InputEngine は Android の Service や View に直接依存せず、完全な単体テスト（Unit Test）が可能になります。
 */
interface InputEngineUiBridge {

    /** 未確定テキスト（Composing Text）を UI / テキストフィールドにセットします。 */
    fun setComposingText(text: CharSequence, newCursorPosition: Int)

    /** 確定テキスト（Committed Text）をテキストフィールドに挿入します。 */
    fun commitText(text: CharSequence, newCursorPosition: Int)

    /** キーコードの Down/Up イベントペア（KEYCODE_DEL等）をテキストフィールドに送信します。 */
    fun sendDownUpKeyEvents(keyCode: Int)

    /** 指定文字数の周囲テキスト（削除用等）を削除します。 */
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean

    /** 候補オブジェクトのリストを UI（候補バーなど）に設定・表示します。 */
    fun setCandidateObjects(candidates: List<Candidate>?, selectFirst: Boolean)

    /** フローティング候補ポップアップ（Flexbox popup）を表示します。 */
    fun showCandidateFlexboxPopup(header: String?, items: List<String>?, selectedIdx: Int)

    /** フローティング候補ポップアップを非表示にします。 */
    fun hideFloatingCandidates()

    /** 候補バー / 候補ビュー全体を非表示にします。 */
    fun hideCandidatesView()

    /** 指定されたインデックスの候補選択ハイライトを要求します。 */
    fun requestChooseCandidate(index: Int)

    /** 候補展開レイアウトのトグル（開閉）を要求します。 */
    fun toggleExpandCandidates()

    /** UI の全体再描画（キーボード状態更新など）を要求します。 */
    fun requestUIUpdate()

    /** フローティング候補表示が有効（設定オン）であるかを取得します。 */
    fun isFloatingCandidateEnabled(): Boolean

    /** カーソル直前のテキストを取得します。 */
    fun getTextBeforeCursor(maxChars: Int): String

    /** 再変換処理を実行します。 */
    fun prepareReConversion(candidate: String): Boolean
}

/**
 * Android の InputService を InputEngineUiBridge に適応させる実装クラスです。
 */
class InputServiceUiBridge(private val service: InputService) : InputEngineUiBridge {

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        val ic = service.currentInputConnection ?: return
        ic.setComposingText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) {
        val ic = service.currentInputConnection ?: return
        ic.commitText(text, newCursorPosition)
    }

    override fun sendDownUpKeyEvents(keyCode: Int) {
        val ic = service.currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val ic = service.currentInputConnection ?: return false
        return ic.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun setCandidateObjects(candidates: List<Candidate>?, selectFirst: Boolean) {
        service.setCandidateObjects(candidates, selectFirst)
    }

    override fun showCandidateFlexboxPopup(header: String?, items: List<String>?, selectedIdx: Int) {
        service.showCandidateFlexboxPopup(header, items, selectedIdx)
    }

    override fun hideFloatingCandidates() {
        service.hideFloatingCandidates()
    }

    override fun hideCandidatesView() {
        service.hideCandidatesView()
    }

    override fun requestChooseCandidate(index: Int) {
        service.requestChooseCandidate(index)
    }

    override fun toggleExpandCandidates() {
        service.toggleExpandCandidates()
    }

    override fun requestUIUpdate() {
        service.requestUIUpdate()
    }

    override fun isFloatingCandidateEnabled(): Boolean {
        return service.isFloatingCandidateEnabled()
    }

    override fun getTextBeforeCursor(maxChars: Int): String {
        return service.getTextBeforeCursor(maxChars)
    }

    override fun prepareReConversion(candidate: String): Boolean {
        return service.prepareReConversion(candidate)
    }
}

/**
 * IMEの InputConnection / uiBridge から LLM (Zenz) 等に渡すクリーンな前文（左コンテキスト）を抽出するユーティリティです。
 */
object ContextExtractor {

    fun getCleanLeftContext(uiBridge: InputEngineUiBridge?, maxLen: Int = 20): String {
        if (uiBridge == null) return ""
        val textBeforeCursor = uiBridge.getTextBeforeCursor(maxLen * 2)
        if (textBeforeCursor.isEmpty()) return ""

        val textStr = textBeforeCursor
        var newlineIdx = -1
        for (i in textStr.length - 1 downTo 0) {
            val c = textStr[i]
            if (c == '\n' || c == '\r') {
                newlineIdx = i
                break
            }
        }

        val contextStr = if (newlineIdx >= 0) {
            textStr.substring(newlineIdx + 1)
        } else {
            textStr
        }

        val cleanBuilder = StringBuilder()
        for (i in contextStr.indices) {
            val c = contextStr[i]
            if (!Character.isISOControl(c)) {
                cleanBuilder.append(c)
            }
        }

        val result = cleanBuilder.toString()
        return if (result.length > maxLen) {
            result.substring(result.length - maxLen)
        } else {
            result
        }
    }
}
