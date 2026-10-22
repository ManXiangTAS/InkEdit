package org.inkedit.epub

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

class EpubBook private constructor(private val contents: LinkedHashMap<String, ByteArray>, private val opfPath: String) {
    var title: String = ""
    var author: String = ""
    var publisher: String = ""
    var language: String = "zh"
    var description: String = ""
    private var cssRule: String? = null
    private var coverBytes: ByteArray? = null

    companion object {
        fun read(input: InputStream): EpubBook {
            val files = linkedMapOf<String, ByteArray>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        require(!entry.name.startsWith("/") && !entry.name.split('/').contains("..")) { "不安全的文件路径" }
                        files[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
            val container = files["META-INF/container.xml"] ?: error("不是有效的 EPUB：缺少 container.xml")
            val containerDoc = parse(container)
            val fullPath = containerDoc.getElementsByTagNameNS("*", "rootfile")
                .item(0)?.attributes?.getNamedItem("full-path")?.nodeValue
                ?: error("缺少 OPF 根文件")
            require(files.containsKey(fullPath)) { "找不到 OPF 文件" }
            val book = EpubBook(files, fullPath)
            val doc = parse(files[fullPath]!!)
            book.title = field(doc, "title")
            book.author = field(doc, "creator")
            book.publisher = field(doc, "publisher")
            book.language = field(doc, "language").ifBlank { "zh" }
            book.description = field(doc, "description")
            return book
        }
        private fun parse(data: ByteArray): Document {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            factory.isXIncludeAware = false
            factory.setExpandEntityReferences(false)
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(data))
        }
        private fun field(doc: Document, local: String): String =
            doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", local).item(0)?.textContent.orEmpty()

        private fun encode(doc: Document): ByteArray {
            val out = ByteArrayOutputStream()
            val t = TransformerFactory.newInstance().newTransformer()
            t.setOutputProperty("encoding", "UTF-8")
            t.transform(DOMSource(doc), StreamResult(out))
            return out.toByteArray()
        }
    }

    fun setLayout(line: Double, indent: Double, paragraph: Double) {
        require(line in 0.8..4.0 && indent in 0.0..8.0 && paragraph in 0.0..6.0) { "排版数值超出范围" }
        cssRule = "p { line-height: ${line}; text-indent: ${indent}em; margin-bottom: ${paragraph}em; }"
    }

    fun setCover(bytes: ByteArray) {
        require(bytes.size in 1..15_000_000) { "封面大小不支持" }
        val jpeg = bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        require(jpeg || png) { "仅支持 JPEG 和 PNG 图片" }
        coverBytes = bytes.copyOf()
    }

    fun write(output: OutputStream) {
        val files = LinkedHashMap(contents)
        val opf = parse(files[opfPath]!!)
        val metadata = opf.getElementsByTagNameNS("*", "metadata").item(0) as? Element
            ?: error("缺少 metadata")
        fun update(tag: String, value: String) {
            val ns = "http://purl.org/dc/elements/1.1/"
            val old = metadata.getElementsByTagNameNS(ns, tag).item(0)
            if (old != null) old.textContent = value
            else if (value.isNotBlank()) metadata.appendChild(opf.createElementNS(ns, "dc:$tag").apply { textContent = value })
        }
        update("title", title)
        update("creator", author)
        update("publisher", publisher)
        update("language", language)
        update("description", description)
        val manifest = opf.getElementsByTagNameNS("*", "manifest").item(0) as? Element
            ?: error("缺少 manifest")
        val base = opfPath.substringBeforeLast('/', "")
        fun relative(path: String) = if (base.isBlank()) path else "$base/$path"
        if (coverBytes != null) {
            val image = coverBytes!!
            val type = if (image[0] == 0x89.toByte()) "image/png" else "image/jpeg"
            val ext = if (type == "image/png") "png" else "jpg"
            val path = "inkedit-cover.$ext"
            files[relative(path)] = image
            val items = manifest.getElementsByTagNameNS("*", "item")
            val oldCovers = (0 until items.length).mapNotNull { items.item(it) as? Element }
                .filter { it.getAttribute("properties").split(' ').contains("cover-image") }
            for (old in oldCovers) {
                old.setAttribute("properties", old.getAttribute("properties").split(' ').filter { it != "cover-image" }.joinToString(" "))
            }
            val itemId = "inkedit-cover"
            val existing = (0 until items.length).mapNotNull { items.item(it) as? Element }.firstOrNull { it.getAttribute("id") == itemId }
            val coverItem = existing ?: opf.createElementNS(manifest.namespaceURI, "item").also { manifest.appendChild(it) }
            coverItem.setAttribute("id", itemId)
            coverItem.setAttribute("href", path)
            coverItem.setAttribute("media-type", type)
            coverItem.setAttribute("properties", "cover-image")
            val metas = metadata.getElementsByTagNameNS("*", "meta")
            val legacy = (0 until metas.length).mapNotNull { metas.item(it) as? Element }.firstOrNull { it.getAttribute("name") == "cover" }
            if (legacy != null) legacy.setAttribute("content", itemId)
        }
        if (cssRule != null) {
            val path = "inkedit-layout.css"
            files[relative(path)] = ("/* InkEdit styles */\n" + cssRule + "\n").toByteArray()
            val items = manifest.getElementsByTagNameNS("*", "item")
            val allItems = (0 until items.length).mapNotNull { items.item(it) as? Element }
            if (allItems.none { it.getAttribute("id") == "inkedit-layout" }) {
                val css = opf.createElementNS(manifest.namespaceURI, "item")
                css.setAttribute("id", "inkedit-layout")
                css.setAttribute("href", path)
                css.setAttribute("media-type", "text/css")
                manifest.appendChild(css)
            }
            for (item in allItems.filter { it.getAttribute("media-type") == "application/xhtml+xml" }) {
                val href = item.getAttribute("href")
                val uri = java.net.URI(relative("placeholder")).resolve(href).normalize().path
                val xhtml = files[uri] ?: continue
                try {
                    val doc = parse(xhtml)
                    val head = doc.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "head").item(0) as? Element ?: continue
                    val links = head.getElementsByTagNameNS("*", "link")
                    if ((0 until links.length).none { (links.item(it) as Element).getAttribute("href") == "inkedit-layout.css" }) {
                        val link = doc.createElementNS("http://www.w3.org/1999/xhtml", "link")
                        link.setAttribute("rel", "stylesheet")
                        val pathToCss = java.net.URI(relative(uri.substringAfterLast('/'))).resolve("inkedit-layout.css")
                        link.setAttribute("href", "inkedit-layout.css")
                        head.appendChild(link)
                        files[uri] = encode(doc)
                    }
                } catch (_: Exception) { /* keep original XHTML if it cannot be parsed */ }
            }
        }
        files[opfPath] = encode(opf)
        ZipOutputStream(output).use { zip ->
            val mime = files.remove("mimetype") ?: "application/epub+zip".toByteArray()
            val crc = CRC32().apply { update(mime) }
            zip.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; this.crc = crc.value })
            zip.write(mime)
            zip.closeEntry()
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
