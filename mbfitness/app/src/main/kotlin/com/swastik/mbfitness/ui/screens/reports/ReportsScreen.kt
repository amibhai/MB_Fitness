package com.swastik.mbfitness.ui.screens.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swastik.mbfitness.R
import com.swastik.mbfitness.data.MemberRepository
import com.swastik.mbfitness.data.MemberRow
import com.swastik.mbfitness.domain.MemberRules
import com.swastik.mbfitness.domain.Money
import com.swastik.mbfitness.domain.MonthPoint
import com.swastik.mbfitness.domain.MonthReport
import com.swastik.mbfitness.domain.PhoneNumbers
import com.swastik.mbfitness.domain.ReportEvent
import com.swastik.mbfitness.domain.ReportPayment
import com.swastik.mbfitness.domain.ReportRules
import com.swastik.mbfitness.domain.TodayProvider
import com.swastik.mbfitness.ui.appViewModel
import com.swastik.mbfitness.ui.components.DueBadge
import com.swastik.mbfitness.ui.components.EmptyState
import com.swastik.mbfitness.ui.components.ListGap
import com.swastik.mbfitness.ui.components.MbCard
import com.swastik.mbfitness.ui.components.MbIconButton
import com.swastik.mbfitness.ui.components.MbTopBar
import com.swastik.mbfitness.ui.components.ScreenPadding
import com.swastik.mbfitness.ui.components.SectionLabel
import com.swastik.mbfitness.ui.theme.MbTheme
import com.swastik.mbfitness.ui.theme.MbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class BalanceLine(val memberId: Long, val name: String, val phone: String, val due: Long)

data class ReportsUiState(
    val loaded: Boolean = false,
    val report: MonthReport? = null,
    /** Which month on the charts is picked, for reading its exact value. */
    val pickedChartMonth: Long? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val balances: List<BalanceLine> = emptyList(),
)

private data class ReportData(val payments: List<ReportPayment>, val events: List<ReportEvent>, val rows: List<MemberRow>)

class ReportsViewModel(members: MemberRepository, private val clock: TodayProvider) : ViewModel() {
    private val month = MutableStateFlow(ReportRules.firstOfMonth(clock.today()))
    private val tick = MutableStateFlow(0)

    private val data = combine(members.observeReportPayments(), members.observeReportEvents(), members.observeMembers()) { payments, events, rows ->
        ReportData(payments, events, rows)
    }

    val uiState: StateFlow<ReportsUiState> =
        combine(month, data, tick) { first, source, _ -> build(first, source) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ReportsUiState())

    fun previousMonth() = month.update { ReportRules.addMonths(it, -1) }

    fun nextMonth() = month.update { ReportRules.addMonths(it, 1) }

    fun pickMonth(firstDay: Long) = month.update { firstDay }

    /** Coming back to the app can mean a new month. */
    fun onResume() = tick.update { it + 1 }

    private fun build(first: Long, source: ReportData): ReportsUiState {
        val today = clock.today()
        val thisMonth = ReportRules.firstOfMonth(today)
        val earliest = ReportRules.earliestMonth(source.payments, source.events, today)
        val shown = first.coerceIn(earliest, thisMonth)
        return ReportsUiState(
            loaded = true,
            report = ReportRules.build(shown, today, source.payments, source.events),
            canGoBack = shown > earliest,
            canGoForward = shown < thisMonth,
            balances = source.rows
                .filter { MemberRules.countsAsPending(it.status) && MemberRules.due(it.balance) > 0 }
                .sortedByDescending { it.balance }
                .map { BalanceLine(it.id, it.name, PhoneNumbers.display(it.phone), MemberRules.due(it.balance)) },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** SPEC 4-E and 10.2 screen 10. */
@Composable
fun ReportsScreen(onMemberClick: (Long) -> Unit) {
    val viewModel = appViewModel { app -> ReportsViewModel(app.members, app.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val report = state.report

    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        MbTopBar("Reports")
        if (report == null) return@Column
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(ListGap),
        ) {
            item(key = "month") { MonthSwitcher(report.title, state.canGoBack, state.canGoForward, viewModel::previousMonth, viewModel::nextMonth) }
            item(key = "tiles") { Tiles(report) }
            item(key = "collections") {
                ChartCard("Collected, last 6 months", report.collections, report.firstDay, Money::format, Money::spoken, viewModel::pickMonth) { points, selected, modifier ->
                    CollectionBars(points, selected, modifier)
                }
            }
            item(key = "active") {
                ChartCard("Active members, last 6 months", report.activeMembers, report.firstDay, { it.toString() }, { it.toString() }, viewModel::pickMonth) { points, selected, modifier ->
                    ActiveLine(points, selected, modifier)
                }
            }
            item(key = "balancesTitle") { SectionLabel("Balances now (${state.balances.size})", Modifier.padding(top = 8.dp)) }
            if (state.balances.isEmpty()) {
                item(key = "noBalances") { EmptyState("Nobody owes anything. Nice!") }
            }
            items(state.balances, key = { it.memberId }) { line ->
                MbCard(Modifier.fillMaxWidth(), onClick = { onMemberClick(line.memberId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MbType.BodyLarge, color = MbTheme.colors.textPrimary)
                            Text(line.phone, style = MbType.Caption, color = MbTheme.colors.textSecondary)
                        }
                        DueBadge(line.due)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthSwitcher(title: String, canGoBack: Boolean, canGoForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    val colors = MbTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (canGoBack) {
            MbIconButton(R.drawable.ic_chevron_right, "Previous month", onBack, Modifier.rotate(180f))
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            title,
            Modifier
                .weight(1f)
                .semantics { heading() },
            style = MbType.BodyLarge,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (canGoForward) {
            MbIconButton(R.drawable.ic_chevron_right, "Next month", onForward)
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun Tiles(report: MonthReport) {
    Column(verticalArrangement = Arrangement.spacedBy(ListGap)) {
        TileRow {
            Tile("Collected", Money.format(report.collected), Modifier.weight(1f), note = report.change, spoken = Money.spoken(report.collected))
            Tile("Pending", Money.format(report.pending), Modifier.weight(1f), spoken = Money.spoken(report.pending))
        }
        TileRow {
            Tile("Active", report.active.toString(), Modifier.weight(1f))
            Tile("Average per member", Money.format(report.averagePerMember), Modifier.weight(1f), spoken = Money.spoken(report.averagePerMember))
        }
        TileRow {
            Tile("New joins", report.newJoins.toString(), Modifier.weight(1f))
            Tile("Renewals", report.renewals.toString(), Modifier.weight(1f))
            Tile("Left", report.left.toString(), Modifier.weight(1f))
        }
    }
}

/** The tiles in a row share a height, so a wrapped label doesn't leave them uneven. */
@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ListGap),
        content = content,
    )
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier, note: String? = null, spoken: String? = null) {
    val colors = MbTheme.colors
    Surface(
        modifier.fillMaxHeight(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
    ) {
        Column(
            Modifier
                .padding(12.dp)
                .clearAndSetSemantics { contentDescription = listOfNotNull("$label: ${spoken ?: value}", note).joinToString(". ") },
        ) {
            Text(value, style = MbType.BodyLarge, color = colors.textPrimary)
            Text(label, style = MbType.Caption, color = colors.textSecondary)
            note?.let { Text(it, style = MbType.Caption, color = colors.textSecondary) }
        }
    }
}

/**
 * One chart, with its months underneath. Each month is a tap target taller and wider than its
 * mark, and picking one moves the whole report there. TalkBack reads each month's value, so
 * nothing depends on seeing the chart.
 */
@Composable
private fun ChartCard(
    title: String,
    points: List<MonthPoint>,
    selected: Long,
    format: (Long) -> String,
    spoken: (Long) -> String,
    onPick: (Long) -> Unit,
    chart: @Composable (List<MonthPoint>, Long, Modifier) -> Unit,
) {
    val colors = MbTheme.colors
    val current = points.firstOrNull { it.firstDay == selected }
    MbCard(Modifier.fillMaxWidth()) {
        Text(title, style = MbType.Label, color = colors.textPrimary)
        current?.let {
            Text(
                "${it.label}: ${format(it.value)}",
                Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MbType.Caption,
                color = colors.textSecondary,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .clearAndSetSemantics { },
        ) {
            chart(points, selected, Modifier.fillMaxSize())
        }
        Row(
            Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            points.forEach { point ->
                val isSelected = point.firstDay == selected
                Text(
                    point.label,
                    Modifier
                        .weight(1f)
                        .selectable(isSelected, role = Role.RadioButton) { onPick(point.firstDay) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 14.dp)
                        .clearAndSetSemantics {
                            contentDescription = "${point.label}: ${spoken(point.value)}"
                        },
                    style = MbType.Caption.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                    color = if (isSelected) colors.textPrimary else colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The chart's colour was checked against both card colours for contrast. */
@Composable
private fun chartColor(): Color = if (MbTheme.colors.isDark) MbTheme.colors.accent else MbTheme.colors.button

/** Bars are 24 dp at most, grow from one baseline, and only round their top corners. */
@Composable
private fun CollectionBars(points: List<MonthPoint>, selected: Long, modifier: Modifier) {
    val bar = chartColor()
    val rule = MbTheme.colors.border
    Canvas(modifier) {
        val highest = points.maxOf { it.value }.coerceAtLeast(1)
        val slot = size.width / points.size
        val barWidth = minOf(MaxBarWidth.toPx(), slot * BarShareOfSlot)
        val corner = BarCorner.toPx()
        drawBaseline(rule)
        points.forEachIndexed { index, point ->
            val height = (point.value.toFloat() / highest) * (size.height - BaselineInset.toPx())
            if (point.value > 0) {
                val left = slot * index + (slot - barWidth) / 2
                val top = size.height - height
                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left,
                            top,
                            left + barWidth,
                            size.height,
                            topLeftCornerRadius = CornerRadius(corner),
                            topRightCornerRadius = CornerRadius(corner),
                        ),
                    )
                }
                // The month being read is solid. The rest step back, in the same colour.
                drawPath(path, if (point.firstDay == selected) bar else bar.copy(alpha = UnselectedAlpha))
            }
        }
    }
}

/**
 * The scale follows the data rather than starting at zero. A gym's count moves by a few people,
 * which would look like a flat line from zero. The picked month's exact number is always above.
 */
@Composable
private fun ActiveLine(points: List<MonthPoint>, selected: Long, modifier: Modifier) {
    val line = chartColor()
    val colors = MbTheme.colors
    val ring = colors.surface
    val rule = colors.border
    Canvas(modifier) {
        val values = points.map { it.value }
        val low = values.min()
        val high = values.max()
        val padding = ((high - low) / PaddingShare).coerceAtLeast(1)
        val bottom = (low - padding).coerceAtLeast(0)
        val top = high + padding
        val slot = size.width / points.size
        fun position(index: Int, value: Long) = Offset(
            slot * index + slot / 2,
            size.height - (value - bottom).toFloat() / (top - bottom) * size.height,
        )
        drawBaseline(rule)
        val path = Path()
        points.forEachIndexed { index, point ->
            val at = position(index, point.value)
            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        drawPath(path, line, style = Stroke(LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        points.forEachIndexed { index, point ->
            if (point.firstDay == selected) {
                val at = position(index, point.value)
                drawCircle(ring, MarkerRadius.toPx() + RingWidth.toPx(), at)
                drawCircle(line, MarkerRadius.toPx(), at)
            }
        }
    }
}

private fun DrawScope.drawBaseline(color: Color) {
    val width = 1.dp.toPx()
    drawRect(color, Offset(0f, size.height - width), Size(size.width, width))
}

private val ChartHeight = 96.dp
private val MaxBarWidth = 24.dp
private val BarCorner = 4.dp
private val BaselineInset = 2.dp
private val LineWidth = 2.dp
private val MarkerRadius = 4.dp
private val RingWidth = 2.dp
private const val BarShareOfSlot = 0.6f
private const val UnselectedAlpha = 0.45f
private const val PaddingShare = 4L
