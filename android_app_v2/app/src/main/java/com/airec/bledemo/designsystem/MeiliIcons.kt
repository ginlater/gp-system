package com.airec.bledemo.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「暖玉柔光」定制线性图标集 —— 1:1 翻译自 warm_2.html 的 `<symbol id="ic-...">` 雪碧图。
 *
 * 统一规范（与原型 `.ic` 一致）：24 栅格、描边 1.75dp、圆端点 [StrokeCap.Round]、
 * 圆角连接 [StrokeJoin.Round]、`fill:none stroke:currentColor`。
 *
 * 红线：绝不用 emoji / Material 默认图标凑数。陪伴=并蒂双瓣花蕊、接诊=日历+心、
 * 待整理=层叠收纳盒、档案=人像、陪伴笔=ic-pen 等，全部还原定制视觉。
 *
 * 用法：`Icon(MeiliIcons.Companion, contentDescription = null, tint = ...)`。
 * （tint 不传时默认随 LocalContentColor，等价于 CSS 的 currentColor。）
 */
object MeiliIcons {

    private const val GRID = 24f
    private const val STROKE = 1.75f

    /** 大多数图标走描边路径的统一构造器。 */
    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = GRID,
            viewportHeight = GRID,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black), // 实际颜色由 Icon 的 tint 覆盖
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** 既有描边又有局部实心填充（如电池电量块）的图标。 */
    private fun mixed(
        name: String,
        strokePart: PathBuilder.() -> Unit,
        fillPart: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = GRID,
            viewportHeight = GRID,
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = strokePart,
            )
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
                pathBuilder = fillPart,
            )
        }.build()

    // === 圆角矩形辅助（CSS rect rx → 四角圆弧）。
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcTo(r, r, 0f, false, true, x + w, y + r)
        lineTo(x + w, y + h - r)
        arcTo(r, r, 0f, false, true, x + w - r, y + h)
        lineTo(x + r, y + h)
        arcTo(r, r, 0f, false, true, x, y + h - r)
        lineTo(x, y + r)
        arcTo(r, r, 0f, false, true, x + r, y)
        close()
    }

    // === 圆（CSS circle）。
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, true, r * 2, 0f)
        arcToRelative(r, r, 0f, true, true, -r * 2, 0f)
        close()
    }

    /** 陪伴/home：双花蕊相伴（并蒂双瓣）。 */
    val Companion: ImageVector by lazy {
        stroke("ic_companion") {
            // 上方双瓣
            moveTo(9f, 13.5f)
            curveToRelative(-2.2f, 0f, -3.6f, -1.7f, -3.6f, -3.5f)
            curveTo(5.4f, 8.2f, 6.8f, 7f, 8.2f, 7f)
            curveToRelative(0.9f, 0f, 1.6f, 0.4f, 2.1f, 1.1f)
            curveToRelative(0.5f, -0.7f, 1.2f, -1.1f, 2.1f, -1.1f)
            curveToRelative(1.4f, 0f, 2.8f, 1.2f, 2.8f, 3f)
            curveToRelative(0f, 1.8f, -1.4f, 3.5f, -3.6f, 3.5f)
            // 下方花托
            moveTo(9f, 13.3f)
            curveToRelative(0f, 2.4f, 1.1f, 4.2f, 3f, 5.2f)
            curveToRelative(1.9f, -1f, 3f, -2.8f, 3f, -5.2f)
            // 花蕊
            moveTo(13.05f, 11f)
            arcToRelative(1.05f, 1.05f, 0f, true, true, -2.1f, 0f)
            arcToRelative(1.05f, 1.05f, 0f, true, true, 2.1f, 0f)
            close()
        }
    }

    /** 接诊/今日：日历 + 心。 */
    val Reception: ImageVector by lazy {
        stroke("ic_reception") {
            roundRect(4f, 5.5f, 16f, 14.5f, 3.4f)
            // 顶部挂环 + 分隔线
            moveTo(8f, 3.5f); verticalLineTo(7f)
            moveTo(16f, 3.5f); verticalLineTo(7f)
            moveTo(4f, 10f); horizontalLineTo(20f)
            // 心
            moveTo(12f, 18.2f)
            curveToRelative(-1.5f, -1f, -2.5f, -1.9f, -2.5f, -3.1f)
            curveToRelative(0f, -0.8f, 0.6f, -1.4f, 1.3f, -1.4f)
            curveToRelative(0.5f, 0f, 0.9f, 0.3f, 1.2f, 0.7f)
            curveToRelative(0.3f, -0.4f, 0.7f, -0.7f, 1.2f, -0.7f)
            curveToRelative(0.7f, 0f, 1.3f, 0.6f, 1.3f, 1.4f)
            curveToRelative(0f, 1.2f, -1f, 2.1f, -2.5f, 3.1f)
            close()
        }
    }

    /** 提醒：铃。 */
    val Reminder: ImageVector by lazy {
        stroke("ic_reminder") {
            moveTo(6.5f, 16.5f)
            curveToRelative(-0.5f, 0f, -0.8f, -0.6f, -0.5f, -1f)
            curveToRelative(0.8f, -1f, 1.3f, -2.1f, 1.3f, -3.4f)
            verticalLineTo(10.3f)
            curveToRelative(0f, -2.6f, 2.1f, -4.8f, 4.7f, -4.8f)
            reflectiveCurveToRelative(4.7f, 2.2f, 4.7f, 4.8f)
            verticalLineToRelative(1.8f)
            curveToRelative(0f, 1.3f, 0.5f, 2.4f, 1.3f, 3.4f)
            curveToRelative(0.3f, 0.4f, 0f, 1f, -0.5f, 1f)
            close()
            moveTo(10.2f, 19f)
            arcToRelative(1.9f, 1.9f, 0f, false, false, 3.6f, 0f)
            moveTo(12f, 5.5f)
            verticalLineTo(3.8f)
        }
    }

    /** 待整理/未归档：层叠收纳盒。 */
    val Tidy: ImageVector by lazy {
        stroke("ic_tidy") {
            moveTo(3.5f, 9.2f); lineTo(12f, 5f); lineTo(20.5f, 9.2f); lineTo(12f, 13.4f); close()
            moveTo(4f, 12.6f); lineTo(12f, 16.6f); lineTo(20f, 12.6f)
            moveTo(4f, 16f); lineTo(12f, 20f); lineTo(20f, 16f)
        }
    }

    /** 我的/档案：人像。 */
    val Profile: ImageVector by lazy {
        stroke("ic_profile") {
            circle(12f, 8.4f, 3.7f)
            moveTo(5.5f, 19.2f)
            curveToRelative(0.6f, -3.3f, 3.3f, -5.4f, 6.5f, -5.4f)
            reflectiveCurveToRelative(5.9f, 2.1f, 6.5f, 5.4f)
        }
    }

    /** 陪伴笔：笔身斜线 + 笔头。 */
    val Pen: ImageVector by lazy {
        stroke("ic_pen") {
            moveTo(6f, 18f); lineTo(16f, 8f)
            moveTo(16f, 8f)
            lineToRelative(1.6f, -1.6f)
            arcToRelative(2.2f, 2.2f, 0f, false, true, 3.1f, 3.1f)
            lineTo(18f, 12.3f)
            moveTo(14.4f, 6.4f); lineTo(17.6f, 9.6f)
            moveTo(6f, 18f); lineToRelative(-1.4f, 1.4f)
            moveTo(4.6f, 19.4f); lineTo(4f, 21f); lineToRelative(1.6f, -0.6f); close()
        }
    }

    /** 同步：双向环形箭头。 */
    val Sync: ImageVector by lazy {
        stroke("ic_sync") {
            moveTo(19f, 11f)
            arcToRelative(7f, 7f, 0f, false, false, -12.3f, -3.4f)
            moveTo(5f, 5.5f); verticalLineTo(9f); horizontalLineToRelative(3.5f)
            moveTo(5f, 13f)
            arcToRelative(7f, 7f, 0f, false, false, 12.3f, 3.4f)
            moveTo(19f, 18.5f); verticalLineTo(15f); horizontalLineToRelative(-3.5f)
        }
    }

    /** 播放/试听：实心三角（CSS 用描边三角，这里同样描边以保线性气质）。 */
    val Play: ImageVector by lazy {
        stroke("ic_play") {
            moveTo(8.5f, 6.7f); lineTo(17f, 12f); lineTo(8.5f, 17.3f); close()
        }
    }

    /** 搜索：放大镜。 */
    val Search: ImageVector by lazy {
        stroke("ic_search") {
            circle(10.8f, 10.8f, 6.2f)
            moveTo(15.5f, 15.5f); lineToRelative(3.8f, 3.8f)
        }
    }

    /** 新增 +。 */
    val Add: ImageVector by lazy {
        stroke("ic_add") {
            moveTo(12f, 5.5f); verticalLineTo(18.5f)
            moveTo(5.5f, 12f); horizontalLineTo(18.5f)
        }
    }

    /** 返回 ‹。 */
    val Back: ImageVector by lazy {
        stroke("ic_back") {
            moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f)
        }
    }

    /** chevron right ›。 */
    val ChevRight: ImageVector by lazy {
        stroke("ic_chev_right") {
            moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f)
        }
    }

    /** chevron down ⌄（折叠箭头）。 */
    val ChevDown: ImageVector by lazy {
        stroke("ic_chev_down") {
            moveTo(5.5f, 9.5f); lineTo(12f, 16f); lineTo(18.5f, 9.5f)
        }
    }

    /** chevron left ‹（日期/分页）。 */
    val ChevLeft: ImageVector by lazy {
        stroke("ic_chev_left") {
            moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f)
        }
    }

    /** 对勾。 */
    val Check: ImageVector by lazy {
        stroke("ic_check") {
            moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
        }
    }

    /** 绑定：相连链环。 */
    val Link: ImageVector by lazy {
        stroke("ic_link") {
            moveTo(10f, 13.5f); lineTo(14f, 9.5f)
            moveTo(8.6f, 15f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, -4.2f)
            lineToRelative(2f, -2f)
            arcToRelative(3f, 3f, 0f, false, true, 4.2f, 0f)
            moveTo(15.4f, 9f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 4.2f)
            lineToRelative(-2f, 2f)
            arcToRelative(3f, 3f, 0f, false, true, -4.2f, 0f)
        }
    }

    /** 解绑：拆开的链 + 四向小裂痕。 */
    val Unbind: ImageVector by lazy {
        stroke("ic_unbind") {
            moveTo(8.6f, 15f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, -4.2f)
            lineToRelative(1f, -1f)
            moveTo(15.4f, 9f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 4.2f)
            lineToRelative(-1f, 1f)
            moveTo(9f, 6.5f); verticalLineTo(4.5f)
            moveTo(6.5f, 9f); horizontalLineTo(4.5f)
            moveTo(15f, 17.5f); verticalLineTo(19.5f)
            moveTo(17.5f, 15f); horizontalLineTo(19.5f)
        }
    }

    /** 上传：上箭头 + 底托。 */
    val Upload: ImageVector by lazy {
        stroke("ic_upload") {
            moveTo(12f, 16f); verticalLineTo(6.5f)
            moveTo(8.5f, 10f); lineTo(12f, 6.3f); lineTo(15.5f, 10f)
            moveTo(5.5f, 18.5f); horizontalLineTo(18.5f)
        }
    }

    /** 更多：三点。 */
    val More: ImageVector by lazy {
        stroke("ic_more") {
            circle(5.5f, 12f, 1.2f)
            circle(12f, 12f, 1.2f)
            circle(18.5f, 12f, 1.2f)
        }
    }

    /** 设置/我的：齿轮（中心圆 + 8 根辐条），用于右上角「我的/设置」入口。 */
    val Settings: ImageVector by lazy {
        stroke("ic_settings") {
            circle(12f, 12f, 3.2f)
            moveTo(12f, 3.8f); lineTo(12f, 6.2f)
            moveTo(12f, 17.8f); lineTo(12f, 20.2f)
            moveTo(3.8f, 12f); lineTo(6.2f, 12f)
            moveTo(17.8f, 12f); lineTo(20.2f, 12f)
            moveTo(6.2f, 6.2f); lineTo(7.9f, 7.9f)
            moveTo(16.1f, 16.1f); lineTo(17.8f, 17.8f)
            moveTo(17.8f, 6.2f); lineTo(16.1f, 7.9f)
            moveTo(7.9f, 16.1f); lineTo(6.2f, 17.8f)
        }
    }

    /** 刷新：单向环形箭头（reception/重跑用）。 */
    val Refresh: ImageVector by lazy {
        stroke("ic_refresh") {
            moveTo(19.5f, 11f)
            arcTo(7.5f, 7.5f, 0f, false, false, 6.5f, 6.5f)
            lineTo(4.5f, 8.5f)
            moveTo(4.5f, 5f); verticalLineTo(8.5f); horizontalLineTo(8f)
            moveTo(4.5f, 13f)
            arcTo(7.5f, 7.5f, 0f, false, false, 17.5f, 17.5f)
            lineTo(19.5f, 15.5f)
            moveTo(19.5f, 19f); verticalLineTo(15.5f); horizontalLineTo(16f)
        }
    }

    /** 手机麦克风（陪伴来源）。 */
    val Phone: ImageVector by lazy {
        stroke("ic_phone") {
            roundRect(7f, 3.5f, 10f, 17f, 2.6f)
            moveTo(10.5f, 17.5f); horizontalLineTo(13.5f)
        }
    }

    /** 警告：三角 + 感叹。 */
    val Warn: ImageVector by lazy {
        stroke("ic_warn") {
            moveTo(12f, 4.5f); lineTo(21f, 19.5f); horizontalLineTo(3f); close()
            moveTo(12f, 10f); verticalLineTo(14f)
            circle(12f, 17f, 0.4f)
        }
    }

    /** 信息：圆 + i。 */
    val Info: ImageVector by lazy {
        stroke("ic_info") {
            circle(12f, 12f, 8f)
            moveTo(12f, 11f); verticalLineTo(16f)
            circle(12f, 8f, 0.5f)
        }
    }

    /** sparkle/预测：双星闪。 */
    val Spark: ImageVector by lazy {
        stroke("ic_spark") {
            moveTo(12f, 4.5f)
            curveToRelative(0.4f, 3.5f, 1.5f, 4.6f, 5f, 5f)
            curveToRelative(-3.5f, 0.4f, -4.6f, 1.5f, -5f, 5f)
            curveToRelative(-0.4f, -3.5f, -1.5f, -4.6f, -5f, -5f)
            curveToRelative(3.5f, -0.4f, 4.6f, -1.5f, 5f, -5f)
            close()
            moveTo(18.5f, 14f)
            curveToRelative(0.2f, 1.5f, 0.6f, 1.9f, 2f, 2f)
            curveToRelative(-1.4f, 0.2f, -1.8f, 0.6f, -2f, 2f)
            curveToRelative(-0.2f, -1.4f, -0.6f, -1.9f, -2f, -2f)
            curveToRelative(1.4f, -0.1f, 1.8f, -0.5f, 2f, -2f)
            close()
        }
    }

    /** 心（匹配/共情）。 */
    val Heart: ImageVector by lazy {
        stroke("ic_heart") {
            moveTo(12f, 19f)
            curveToRelative(-3f, -2f, -7f, -4.8f, -7f, -8.6f)
            curveTo(5f, 8f, 6.8f, 6.5f, 8.8f, 6.5f)
            curveToRelative(1.3f, 0f, 2.4f, 0.6f, 3.2f, 1.7f)
            curveToRelative(0.8f, -1.1f, 1.9f, -1.7f, 3.2f, -1.7f)
            curveToRelative(2f, 0f, 3.8f, 1.5f, 3.8f, 3.9f)
            curveTo(19f, 14.2f, 15f, 17f, 12f, 19f)
            close()
        }
    }

    /** 收藏/星。 */
    val Star: ImageVector by lazy {
        stroke("ic_star") {
            moveTo(12f, 4.5f)
            lineTo(14.3f, 9.3f); lineTo(19.5f, 10f); lineTo(15.7f, 13.8f)
            lineTo(16.7f, 19f); lineTo(12f, 16.4f); lineTo(7.3f, 19f); lineTo(8.3f, 13.8f)
            lineTo(4.5f, 10f); lineTo(9.7f, 9.3f); close()
        }
    }

    /** 删除：垃圾桶。 */
    val Trash: ImageVector by lazy {
        stroke("ic_trash") {
            moveTo(5.5f, 7f); horizontalLineTo(18.5f)
            moveTo(9.5f, 7f); verticalLineTo(5.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
            horizontalLineToRelative(2f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
            verticalLineTo(7f)
            moveTo(7f, 7f)
            lineToRelative(0.8f, 11f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 1.9f)
            horizontalLineToRelative(4.4f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -1.9f)
            lineTo(17f, 7f)
        }
    }

    /** 目标：同心圆。 */
    val Target: ImageVector by lazy {
        stroke("ic_target") {
            circle(12f, 12f, 7.5f)
            circle(12f, 12f, 3.7f)
        }
    }

    /** 路线/规划。 */
    val Route: ImageVector by lazy {
        stroke("ic_route") {
            circle(6.5f, 6.5f, 2.2f)
            circle(17.5f, 17.5f, 2.2f)
            moveTo(8.7f, 6.5f)
            horizontalLineToRelative(6f)
            arcToRelative(3f, 3f, 0f, false, true, 0f, 6f)
            horizontalLineToRelative(-5.4f)
            arcToRelative(3f, 3f, 0f, false, false, 0f, 6f)
            horizontalLineToRelative(6f)
        }
    }

    /** 趋势。 */
    val Trend: ImageVector by lazy {
        stroke("ic_trend") {
            moveTo(4.5f, 15.5f); lineTo(9f, 11f); lineTo(12f, 13.5f); lineTo(19f, 6.5f)
            moveTo(14.5f, 6.5f); horizontalLineTo(19f); verticalLineTo(11f)
        }
    }

    /** 文档/清单。 */
    val Doc: ImageVector by lazy {
        stroke("ic_doc") {
            moveTo(7f, 4.5f)
            horizontalLineToRelative(6.5f)
            lineTo(18f, 9f)
            verticalLineToRelative(10f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
            horizontalLineToRelative(-9f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6f, 19f)
            verticalLineTo(6f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1f, -1.5f)
            close()
            moveTo(13f, 4.5f); verticalLineTo(9f); horizontalLineTo(17.5f)
            moveTo(9f, 13f); horizontalLineTo(15f)
            moveTo(9f, 16f); horizontalLineTo(13f)
        }
    }

    /** 钻石/价值。 */
    val Gem: ImageVector by lazy {
        stroke("ic_gem") {
            moveTo(7f, 4.5f); horizontalLineToRelative(10f); lineTo(20f, 9f); lineTo(12f, 19f); lineTo(4f, 9f); close()
            moveTo(4f, 9f); horizontalLineTo(20f)
            moveTo(9.5f, 4.5f); lineTo(8f, 9f); lineTo(12f, 18.5f)
            moveTo(14.5f, 4.5f); lineTo(16f, 9f); lineTo(12f, 18.5f)
        }
    }

    /** 头戴/原始音：耳机。 */
    val Headphone: ImageVector by lazy {
        stroke("ic_headphone") {
            moveTo(5f, 13f); verticalLineTo(12f)
            arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
            verticalLineToRelative(1f)
            moveTo(5f, 13f)
            horizontalLineToRelative(1.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 8f, 14.5f)
            verticalLineToRelative(2f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 18f)
            horizontalLineTo(6f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            close()
            moveTo(19f, 13f)
            horizontalLineToRelative(-1.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, false, -1.5f, 1.5f)
            verticalLineToRelative(2f)
            arcToRelative(1.5f, 1.5f, 0f, false, false, 1.5f, 1.5f)
            horizontalLineToRelative(0.5f)
            arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
            close()
        }
    }

    /** 时钟/待。 */
    val Clock: ImageVector by lazy {
        stroke("ic_clock") {
            circle(12f, 12f, 7.5f)
            moveTo(12f, 8f); verticalLineToRelative(4.2f); lineToRelative(2.8f, 1.8f)
        }
    }

    /** 锁。 */
    val Lock: ImageVector by lazy {
        stroke("ic_lock") {
            roundRect(5.5f, 10.5f, 13f, 9f, 2.4f)
            moveTo(8.5f, 10.5f); verticalLineTo(8f)
            arcToRelative(3.5f, 3.5f, 0f, false, true, 7f, 0f)
            verticalLineToRelative(2.5f)
        }
    }

    /** wifi/信号。 */
    val Wifi: ImageVector by lazy {
        stroke("ic_wifi") {
            moveTo(4f, 9.5f)
            arcToRelative(12f, 12f, 0f, false, true, 16f, 0f)
            moveTo(6.5f, 13f)
            arcToRelative(8f, 8f, 0f, false, true, 11f, 0f)
            moveTo(9f, 16.3f)
            arcToRelative(4f, 4f, 0f, false, true, 6f, 0f)
            circle(12f, 19f, 0.5f)
        }
    }

    /** 电池（带电量实心块）。 */
    val Battery: ImageVector by lazy {
        mixed(
            name = "ic_battery",
            strokePart = {
                roundRect(3f, 8f, 16f, 8f, 2f)
                moveTo(21f, 11f); verticalLineTo(13f)
            },
            fillPart = {
                // rx 1 的电量块 5,10 9×4
                moveTo(6f, 10f)
                lineTo(13f, 10f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
                verticalLineTo(13f)
                arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
                lineTo(6f, 14f)
                arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
                verticalLineTo(11f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                close()
            },
        )
    }

    /** 皮肤/主题：调色板。 */
    val Palette: ImageVector by lazy {
        stroke("ic_palette") {
            moveTo(12f, 4.5f)
            arcToRelative(7.5f, 7.5f, 0f, false, false, 0f, 15f)
            curveToRelative(1.2f, 0f, 1.8f, -0.9f, 1.8f, -1.8f)
            curveToRelative(0f, -1.2f, -1f, -1.5f, -1f, -2.5f)
            curveToRelative(0f, -0.7f, 0.6f, -1.2f, 1.4f, -1.2f)
            horizontalLineTo(17f)
            arcToRelative(3f, 3f, 0f, false, false, 3f, -3f)
            curveToRelative(0f, -3.6f, -3.6f, -6.5f, -8f, -6.5f)
            close()
            circle(8.5f, 11f, 0.6f)
            circle(12f, 8.5f, 0.6f)
            circle(15.5f, 11f, 0.6f)
        }
    }

    /** 回顾/翻看：相册。 */
    val Album: ImageVector by lazy {
        stroke("ic_album") {
            roundRect(4f, 4.5f, 16f, 15f, 3f)
            moveTo(4f, 14.5f); lineTo(8f, 11f); lineTo(12f, 14f); lineTo(15f, 11.5f); lineTo(20f, 16f)
            circle(9f, 9f, 1.3f)
        }
    }

    /** 评论/点评气泡。 */
    val Comment: ImageVector by lazy {
        stroke("ic_comment") {
            moveTo(5f, 6.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 5f)
            horizontalLineToRelative(11f)
            arcTo(1.5f, 1.5f, 0f, false, true, 19f, 6.5f)
            verticalLineToRelative(8f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
            horizontalLineTo(10f)
            lineToRelative(-4f, 3.5f)
            verticalLineTo(16f)
            horizontalLineTo(6.5f)
            arcTo(1.5f, 1.5f, 0f, false, true, 5f, 14.5f)
            close()
        }
    }

    /** 声波/逐字转写。 */
    val Waveform: ImageVector by lazy {
        stroke("ic_waveform") {
            moveTo(4f, 11f); verticalLineToRelative(2f)
            moveTo(7.5f, 8f); verticalLineToRelative(8f)
            moveTo(11f, 5.5f); verticalLineToRelative(13f)
            moveTo(14.5f, 8.5f); verticalLineToRelative(7f)
            moveTo(18f, 10f); verticalLineToRelative(4f)
            moveTo(21f, 11f); verticalLineToRelative(2f)
        }
    }

    /** 任务清单（带勾选格）。 */
    val Tasks: ImageVector by lazy {
        stroke("ic_tasks") {
            roundRect(4f, 4.5f, 6f, 6f, 1.6f)
            moveTo(5.5f, 7.4f); lineTo(6.7f, 8.6f); lineTo(8.6f, 6.3f)
            roundRect(4f, 13.5f, 6f, 6f, 1.6f)
            moveTo(13.5f, 6.5f); horizontalLineTo(20f)
            moveTo(13.5f, 15.5f); horizontalLineTo(20f)
        }
    }
}
