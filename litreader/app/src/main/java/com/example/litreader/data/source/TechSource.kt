package com.example.litreader.data.source

/** 技術討論區（fid=7）：无 type 子分类，站点页按需抓取（不做本地目录全量同步）。 */
class TechSource : T66yBaseSource() {
    override val id = "t66y_tech"
    override val name = "技術討論區"
    override val shortName = "技术"
    override val fid = "7"
    override val remotePageSize = 100
    override val style = SourceStyle.POSTS

    override val categories = listOf("" to "全部")
}
