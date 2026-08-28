package com.example.litreader.data.source

/** 達蓋爾的旗幟（fid=16，自拍/贴图区）：无 type 分类，每页 100 条。 */
class GallerySource : T66yBaseSource() {
    override val id = "t66y_img"
    override val name = "達蓋爾的旗幟"
    override val shortName = "贴图"
    override val fid = "16"
    override val remotePageSize = 100
    override val style = SourceStyle.IMAGE

    override val categories = listOf("" to "全部")
}
