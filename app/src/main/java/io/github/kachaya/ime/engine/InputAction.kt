package io.github.kachaya.ime.engine

/**
 * キー入力（ショートカットキーやキー割り当て）で実行されるアクションの定義です。
 */
enum class InputAction(val id: String, val label: String) {
    NONE("none", "なし (割り当てなし)"),
    CURSOR_LEFT("cursor_left", "左移動 / 前候補"),
    CURSOR_RIGHT("cursor_right", "右移動 / 次候補"),
    CURSOR_UP("cursor_up", "上移動 / 前候補"),
    CURSOR_DOWN("cursor_down", "下移動 / 次候補"),
    FORWARD_DELETE("forward_delete", "カーソル右削除 (Forward Delete)"),
    LINE_START("line_start", "行頭移動 (Line Start)"),
    LINE_END("line_end", "行末移動 (Line End)"),
    KILL_LINE("kill_line", "行末まで削除 (Kill Line)"),
    KILL_LINE_BACKWARD("kill_line_backward", "行頭まで削除"),
    LAUNCH_SETTINGS("launch_settings", "設定画面を開く"),
    KANA_KEY("kana_key", "確定 / かなキー"),
    CANCEL("cancel", "キャンセル"),
    TOGGLE_KANA("toggle_kana", "かな / カナ切替"),
    TOGGLE_EN_JP("toggle_en_jp", "英語 / 日本語切替"),
    DELETE_CHAR("delete_char", "1文字削除"),
    COMPLETION("completion", "補完"),
    OPEN_EMOJI("open_emoji", "絵文字ピッカーを開く"),
    INSERT_NEWLINE("insert_newline", "送信せずに改行 (Shift+Enter)");

    companion object {
        /**
         * 識別 ID から対応する InputAction を取得します。
         *
         * @param id 識別 ID 文字列
         * @return 対応する InputAction。該当しない場合は NONE
         */
        fun fromId(id: String?): InputAction {
            if (id == null) {
                return NONE
            }
            for (action in entries) {
                if (action.id == id) {
                    return action
                }
            }
            return NONE
        }
    }
}
