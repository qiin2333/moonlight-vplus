package com.limelight.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Compose 侧首页背景强调色取值。
 *
 * 与 UiHelper.accentColor 同源：解析主题里的 ?attr/appAccent*，在开启
 * "强调色跟随壁纸"后返回壁纸动态色，否则是品牌粉。原代码里
 * colorResource(R.color.ui_shell_accent 系) 的位置改用这里——colorResource
 * 读的是静态资源，感知不到主题 overlay。
 */
@Composable
@ReadOnlyComposable
fun appAccentColor(): Color = Color(UiHelper.accentColor(LocalContext.current))

@Composable
@ReadOnlyComposable
fun appAccentSoftColor(): Color = Color(UiHelper.accentSoftColor(LocalContext.current))

@Composable
@ReadOnlyComposable
fun appAccentFocusColor(): Color = Color(UiHelper.accentFocusColor(LocalContext.current))
