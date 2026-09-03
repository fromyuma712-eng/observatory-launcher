package com.yuma.observatorylauncher.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.WebShortcut
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
/*
 * サイト（URL・端末内のHTML）をホームへ置くための編集面。
 */

/** URL入力またはAndroidのファイル選択から、サイト項目を宣言へ追加する。 */
@Composable
internal fun SiteEditorPanel(
    existing: LayoutConfig.Site?,
    onSave: (title: String, location: String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember(existing?.id) {
        mutableStateOf(TextFieldValue(existing?.title.orEmpty()))
    }
    var location by remember(existing?.id) {
        mutableStateOf(TextFieldValue(existing?.location.orEmpty()))
    }
    var error by remember(existing?.id) { mutableStateOf<String?>(null) }
    val pickHtml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val selected = WebShortcut.normalizeLocation(uri.toString())
            if (selected == null) {
                error = "このHTMLファイルは継続して開けません"
            } else {
                onSave(WebShortcut.title(context, title.text, selected), selected)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter, vertical = 8.dp)
            .border(ObservatoryDimens.Hairline, ObservatoryColors.Line)
            .padding(top = 12.dp),
    ) {
        ObservatoryText(
            text = if (existing == null) "サイト・HTMLをホームに追加" else "サイト・HTMLを編集",
            color = ObservatoryColors.EmberText,
            size = ObservatoryType.Body,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        SiteField(
            label = "表示する名前（空欄なら自動）",
            value = title,
            onValueChange = { title = it; error = null },
        )
        SiteField(
            label = "URL",
            value = location,
            onValueChange = { location = it; error = null },
        )
        if (error != null) {
            ObservatoryText(
                text = error.orEmpty(),
                color = ObservatoryColors.EmberText,
                size = ObservatoryType.Note,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        ActionStrip(
            actions = listOf(
                LedgerAction(if (existing == null) "URLを追加" else "URLを更新") {
                    val normalized = WebShortcut.normalizeLocation(location.text)
                    if (normalized == null) {
                        error = "URLを確認してください"
                    } else {
                        onSave(WebShortcut.title(context, title.text, normalized), normalized)
                    }
                },
                LedgerAction("HTMLファイルを選ぶ") {
                    pickHtml.launch(arrayOf("text/html", "application/xhtml+xml"))
                },
                LedgerAction("閉じる", onClose),
            ),
            startPadding = 12.dp,
        )
    }
}

@Composable
internal fun SiteField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        ObservatoryText(text = label, color = ObservatoryColors.Muted, size = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(ObservatoryColors.Ember),
            textStyle = TextStyle(
                color = ObservatoryColors.Paper,
                fontSize = 14.sp,
                fontFamily = FontFamily.SansSerif,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .border(ObservatoryDimens.Hairline, ObservatoryColors.Line)
                .padding(horizontal = 10.dp, vertical = 11.dp),
        )
    }
}
