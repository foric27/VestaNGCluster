package ru.foric27.cluster.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.foric27.cluster.R

/**
 * Корневой адаптивный wrapper для экранов Activity.
 *
 * Применяет padding из [R.dimen.screen_content_padding], обрабатывает [WindowInsets]
 * (safeDrawingPadding) и опционально добавляет verticalScroll.
 *
 * @param useScroll если true — добавляет verticalScroll
 * @param content содержимое экрана
 */
@Composable
fun AdaptiveScreen(
    useScroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    val padding = dimensionResource(R.dimen.screen_content_padding)
    val modifier = if (useScroll) {
        Modifier
            .fillMaxSize()
            .padding(padding)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
    } else {
        Modifier
            .fillMaxSize()
            .padding(padding)
            .safeDrawingPadding()
    }

    Box(modifier = modifier) {
        content()
    }
}

/**
 * Адаптивная Card с padding из [R.dimen.screen_card_padding] и fillMaxWidth.
 *
 * @param modifier дополнительный Modifier
 * @param content содержимое карточки
 */
@Composable
fun AdaptiveCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val padding = dimensionResource(R.dimen.screen_card_padding)
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark2),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}

/**
 * Адаптивный Text с размером из dimen ресурса.
 *
 * @param text текст
 * @param textSizeDimen id dimen ресурса для размера шрифта
 * @param color цвет текста (по умолчанию onSurface)
 * @param fontWeight вес шрифта
 * @param maxLines максимальное количество строк
 * @param modifier Modifier
 */
@Composable
fun AdaptiveText(
    text: String,
    textSizeDimen: Int,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    val fontSize = dimensionResource(textSizeDimen)
    Text(
        text = text,
        color = color,
        fontSize = fontSize.value.sp,
        fontWeight = fontWeight,
        maxLines = maxLines,
        modifier = modifier,
    )
}

/**
 * Адаптивный Spacer с размером из dimen ресурса.
 *
 * @param dimen id dimen ресурса
 * @param isVertical если true — HeightSpacer, иначе WidthSpacer
 */
@Composable
fun AdaptiveSpacer(
    dimen: Int,
    isVertical: Boolean = true,
) {
    val size = dimensionResource(dimen)
    if (isVertical) {
        Spacer(Modifier.height(size))
    } else {
        Spacer(Modifier.width(size))
    }
}

/**
 * Адаптивная двухколоночная разметка.
 *
 * При ширине >= 600dp отображает две колонки с weight(1f) и verticalScroll в каждой.
 * При узком экране — одна колонка с verticalScroll.
 *
 * @param leftColumn содержимое левой/единственной колонки
 * @param rightColumn содержимое правой колонки (игнорируется при узком экране)
 */
@Composable
fun AdaptiveTwoColumn(
    leftColumn: @Composable () -> Unit,
    rightColumn: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 600

    if (isWide) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.screen_block_spacing)),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                leftColumn()
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                rightColumn()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            leftColumn()
        }
    }
}

/**
 * Адаптивный LazyColumn с padding и spacing из dimens.
 *
 * @param content содержимое LazyColumn
 */
@Composable
fun AdaptiveLazyColumn(
    content: LazyListScope.() -> Unit,
) {
    val padding = dimensionResource(R.dimen.screen_content_padding)
    val spacing = dimensionResource(R.dimen.screen_section_spacing)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Адаптивный Row с spacing из dimens.
 *
 * @param modifier Modifier
 * @param verticalAlignment вертикальное выравнивание
 * @param content содержимое Row
 */
@Composable
fun AdaptiveRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val spacing = dimensionResource(R.dimen.screen_block_spacing)
    Row(
        modifier = modifier,
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Адаптивный Column с spacing из dimens.
 *
 * @param modifier Modifier
 * @param horizontalAlignment горизонтальное выравнивание
 * @param content содержимое Column
 */
@Composable
fun AdaptiveColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = dimensionResource(R.dimen.screen_section_spacing)
    Column(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
