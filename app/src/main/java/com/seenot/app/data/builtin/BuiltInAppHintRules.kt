package com.seenot.app.data.builtin

/**
 * Invisible factory rules that are always applied for selected apps.
 *
 * These rules are intentionally not persisted as user hints, so they do not
 * appear in preset lists, supplemental-rule management, or exported config.
 */
object BuiltInAppHintRules {
    fun getAppGeneralHints(packageName: String): List<String> {
        return when (packageName) {
            "com.tencent.mm" -> wechatRules
            "com.tencent.mobileqq" -> qqRules
            "com.rednote.app" -> rednoteRules
            "com.taobao.taobao" -> taobaoRules
            else -> emptyList()
        }
    }

    private val wechatRules = listOf<String>(
        "微信全屏图片预览页（特征为顶部无导航栏、底部可能有多个点，且无视频号特有的作者头像、关注按钮或视频进度条）属于本地相册查看或图片详情浏览场景，可能出现在微信的各个功能中，不应被误判为朋友圈信息流、视频号内容或公众号文章内容。",
        "微信全局搜索界面（特征为顶部搜索栏带“取消”按钮，并显示联系人、文件、小程序等分类筛选入口）属于导航工具页面，不构成聊天会话或消息内容查看。",
        "如果只是文章列表，那不属于公众号文章内容。同时，如果页面其实是微信公众号单条内容的评论区详情页（特征为顶部有返回按钮、底部固定显示当前作者信息及互动栏）属于社交互动场景，不应被识别为朋友圈或视频号内容信息流。"
    )

    private val qqRules = listOf<String>(
        "QQ 的“谁赞过我”列表页（通常有蓝色顶部栏展示获赞统计，并以列表形式展示给当前账号内容点赞的用户）属于具体的互动记录页，不等同于进入 QQ 空间模块浏览动态或信息流。",
        "如果页面只是某条内容的详情页（例如文章、新闻或帖子），且没有 QQ 空间的明确界面标识（如“QQ 空间”标题、个人头像/主页入口、专门的空间底部导航等），不应判定为进入 QQ 空间模块。",
        "QQ 底部导航里的“动态”页是一个聚合入口，会汇总空间、频道、群等多种来源的更新；停留在这个页面，或只是看到其中的“QQ 空间动态”等列表入口，都不算进入具体的 QQ 空间模块，只有真正点进专门的空间界面才算；频道等界面同理。"
    )

    private val rednoteRules = listOf<String>(
        "如果全屏内容底部有进度条，就很可能是在看视频内容；否则很可能不是视频内容。",
        "小红书“发现”页就是首页的多条帖子的信息流，也就是小红书首页。评论区等不是“发现”页面。",
        "在小红书“发现”页（首页信息流）中，仅展示内容的封面、标题或缩略图的卡片不算查看对应的内容；只有当用户点击进入详情页并实际播放具体内容（如视频/某种内容）时，才判定为查看对应的内容。"
    )

    private val taobaoRules = listOf<String>(
       "若页面背景或底层透出商品瀑布流，但前景存在全屏各类弹窗遮挡，该页面就不应被识别为包含推荐列表的混合页，因弹窗占据视觉中心。"
    )
}
