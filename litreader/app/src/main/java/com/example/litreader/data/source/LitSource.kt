package com.example.litreader.data.source

/** 成人文學區（fid=20）。 */
class LitSource : T66yBaseSource() {
    override val id = "t66y_lit"
    override val name = "文學交流"
    override val shortName = "文学"
    override val fid = "20"
    override val remotePageSize = 30
    override val style = SourceStyle.TEXT

    override val categories = listOf(
        "" to "全部",
        "1" to "現代奇幻",
        "2" to "古典武俠",
        "3" to "另類禁忌",
        "4" to "性愛技巧",
        "5" to "笑話連篇",
        "6" to "有声小说",
        "12" to "其他交流"
    )
}
