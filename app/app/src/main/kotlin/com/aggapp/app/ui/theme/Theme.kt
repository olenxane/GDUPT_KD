package com.aggapp.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 淡绿清新风亮色：白面 + 淡绿容器 + 清新绿主色 */
private val LightColors = lightColorScheme(
    primary = Brand.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF2E0),
    onPrimaryContainer = Color(0xFF0A3D17),
    secondary = Brand.Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF0EC),
    onSecondaryContainer = Color(0xFF003733),
    tertiary = Brand.Amber,
    onTertiary = Color(0xFF3D2400),
    tertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF3FAF4),
    onBackground = Color(0xFF161D17),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D17),
    surfaceVariant = Color(0xFFE1EDE2),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
)

/** 淡绿清新风暗色：深绿底 + 青绿字面 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD67D),
    onPrimary = Color(0xFF0E390B),
    primaryContainer = Color(0xFF2C5222),
    onPrimaryContainer = Color(0xFFB8F196),
    secondary = Color(0xFF80D5C8),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFF9CF2E4),
    tertiary = Color(0xFFFFB877),
    onTertiary = Color(0xFF4A2600),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    background = Color(0xFF0F150F),
    onBackground = Color(0xFFDEE4DC),
    surface = Color(0xFF161D17),
    onSurface = Color(0xFFDEE4DC),
    surfaceVariant = Color(0xFF27332A),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938C),
)

/** 圆角体系：大卡 24dp，中卡 16dp，胶囊 full */
val CardShapeLarge = RoundedCornerShape(24.dp)
val CardShapeMedium = RoundedCornerShape(16.dp)

@Composable
fun AggTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = MaterialTheme.shapes.copy(
            large = CardShapeLarge,
            medium = CardShapeMedium,
        ),
        content = content,
    )
}
