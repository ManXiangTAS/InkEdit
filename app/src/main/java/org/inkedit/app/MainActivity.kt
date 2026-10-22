package org.inkedit.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.inkedit.epub.EpubBook

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var book by remember { mutableStateOf<EpubBook?>(null) }
                var title by remember { mutableStateOf("") }
                var author by remember { mutableStateOf("") }
                var publisher by remember { mutableStateOf("") }
                var description by remember { mutableStateOf("") }
                var language by remember { mutableStateOf("zh") }
                var lineHeight by remember { mutableStateOf("1.6") }
                var indent by remember { mutableStateOf("2") }
                var paragraphGap by remember { mutableStateOf("0") }
                var status by remember { mutableStateOf("选择 EPUB 开始编辑") }
                var cover by remember { mutableStateOf<ByteArray?>(null) }
                val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) try {
                        val loaded = contentResolver.openInputStream(uri)!!.use { EpubBook.read(it) }
                        book = loaded
                        title = loaded.title
                        author = loaded.author
                        publisher = loaded.publisher
                        description = loaded.description
                        language = loaded.language
                        cover = null
                        status = "已打开 EPUB"
                    } catch (e: Exception) { status = "打开失败：" + e.message }
                }
                val selectCover = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    if (uri != null) try {
                        cover = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        status = "已选择新封面，保存时写入"
                    } catch (e: Exception) { status = "读取封面失败：" + e.message }
                }
                val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri: Uri? ->
                    if (uri != null) try {
                        val current = book ?: error("请先打开 EPUB")
                        current.title = title
                        current.author = author
                        current.publisher = publisher
                        current.description = description
                        current.language = language
                        current.setLayout(lineHeight.toDouble(), indent.toDouble(), paragraphGap.toDouble())
                        if (cover != null) current.setCover(cover!!)
                        contentResolver.openOutputStream(uri, "wt")!!.use { current.write(it) }
                        status = "EPUB 已保存"
                    } catch (e: Exception) { status = "保存失败：" + e.message }
                }

                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("InkEdit · 墨编", style = MaterialTheme.typography.headlineMedium)
                    Text("离线 EPUB 编辑器 · v0.1")
                    Button(onClick = { open.launch(arrayOf("application/epub+zip", "application/zip", "*/*")) }) { Text("打开 EPUB") }
                    Text(status)
                    if (book != null) {
                        HorizontalDivider()
                        Text("元数据", style = MaterialTheme.typography.titleLarge)
                        @Composable fun field(value: String, changed: (String) -> Unit, label: String) {
                            OutlinedTextField(value, changed, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
                        }
                        field(title, { title = it }, "书名")
                        field(author, { author = it }, "作者")
                        field(publisher, { publisher = it }, "出版社")
                        field(language, { language = it }, "语言")
                        field(description, { description = it }, "简介")
                        HorizontalDivider()
                        Text("封面", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(onClick = { selectCover.launch("image/*") }) { Text("选择新封面图片") }
                        HorizontalDivider()
                        Text("排版样式", style = MaterialTheme.typography.titleLarge)
                        field(lineHeight, { lineHeight = it }, "行距倍数（例如 1.6）")
                        field(indent, { indent = it }, "首行缩进（em）")
                        field(paragraphGap, { paragraphGap = it }, "段后间距（em）")
                        Button(onClick = { save.launch("InkEdit.epub") }) { Text("另存为 EPUB") }
                        Text("将排版规则写入书籍内部 CSS。不同阅读器可能覆盖这些样式。")
                    }
                }
            }
        }
    }
}
