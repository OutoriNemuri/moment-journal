package com.nemuri.journal.md

/**
 * 「时刻日记」正文格式：**隐式扩展 Markdown**。
 *
 * 正文以 Markdown 源文本为唯一真相，样式与内联嵌入全部编码在文本里。
 * 本文件是格式的权威定义，任何解析/生成都必须与此处保持一致。
 *
 * ---
 * ## 内联嵌入（本应用扩展，标准 Markdown 没有）
 *
 * | 语义 | 语法 | 示例 |
 * |---|---|---|
 * | 时间点 | `![time](<ISO-8601 带时区>)` | `![time](2026-10-10T20:00:00+08:00)` |
 * | 地点   | `![loc](<lat>,<lon>,<名称>)`     | `![loc](31.2304,121.4737,上海)` |
 * | 图片   | `![img](<私有目录下的相对路径>)` | `![img](images/3f2a1b.jpg)` |
 *
 * 图片只存相对路径，字节落在应用私有目录 `files/` 下，绝不写入数据库。
 *
 * ---
 * ## 支持的样式
 *
 * `**粗体**` `*斜体*` `__下划线__` `~~删除线~~` `# 标题` `- 列表`
 *
 * ---
 * ## 为什么用图片语法承载嵌入
 *
 * `![alt](target)` 的 target 处于圆括号内，天然不需要转义 `]`，
 * 而 `alt` 字段可以复用来承载类型标签 —— 解析无歧义，且正文在纯文本
 * 编辑器里仍然可读。地点与图片沿用同一形状，扩展新类型只需增加一个标签。
 *
 * ---
 * ## 格式演进规则
 *
 * 1. **只增不改**：已定义的标签语义永不改变，否则历史日记会错解。
 * 2. 未知标签按**普通图片**降级渲染（显示原样文本），不抛异常、不丢数据。
 * 3. 新增标签必须在此处登记，并在 [MomentMarkdownParser] 中实现。
 */
object MomentMarkdown {

    const val TAG_TIME = "time"
    const val TAG_LOCATION = "loc"
    const val TAG_IMAGE = "img"

    /** 图片在私有目录中的根目录名。 */
    const val IMAGE_DIR = "images"

    private val TIME_PATTERN = Regex("""!\[time]\(([^)]*)\)""")
    private val LOCATION_PATTERN = Regex("""!\[loc]\(([^)]*)\)""")
    private val IMAGE_PATTERN = Regex("""!\[img]\(([^)]*)\)""")

    fun timeToken(iso: String): String = "![$TAG_TIME]($iso)"

    fun locationToken(lat: Double, lon: Double, name: String): String =
        "![$TAG_LOCATION]($lat,$lon,$name)"

    fun imageToken(relativePath: String): String = "![$TAG_IMAGE]($relativePath)"

    /** 生成图片相对路径，形如 `images/<id>.<ext>`。 */
    fun imageRelativePath(fileName: String): String = "$IMAGE_DIR/$fileName"

    internal fun matchTime(raw: String): String? = TIME_PATTERN.find(raw)?.groupValues?.get(1)
    internal fun matchLocation(raw: String): String? = LOCATION_PATTERN.find(raw)?.groupValues?.get(1)
    internal fun matchImage(raw: String): String? = IMAGE_PATTERN.find(raw)?.groupValues?.get(1)
}
