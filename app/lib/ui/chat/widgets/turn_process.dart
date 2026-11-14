// The whole-turn fold (DSH Web's foldCompletedTurns, dsh-TUI's settled groups)
// for the native chat list.

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';

import '../../../l10n/l10n.dart';
import '../../theme/app_icons.dart';
import '../../theme/theme.dart';
import '../chat_rows.dart';
import 'quiet_dots.dart';
import 'stream_row.dart';

/// 一轮的过程收成的一行:一行安静的摘要,点开在下面按原样列出思考、
/// 工具调用和中间消息,左边一道细线表示"属于这一轮"([StreamThread])。
///
/// ```
/// ✓ 已完成 · 7 步                    ⌄
/// │ ⚛ 思考 · 先看 pubspec…
/// │ ▣ 读取 · lib/main.dart
/// ⚛ 思考中… · 3 步                   ⌄   ← 还在跑的轮,一样能点开
/// ⊘ 已中断 · 2 步                    ⌄
/// ⚠ 出错 · 4 步                      ⌄
/// ```
///
/// 用时不写在这里:回答下面的回合脚注已经有了。
///
/// 打开会话时已有的行在列表里是"往上长"的(见 ChatList 的两段 sliver),
/// 直接展开会把摘要行顶上去。这里自己驱动展开动画,每一帧在布局之前把
/// 滚动位置补回长出来的高度:摘要行不动,内容向下展开。上面的内容不够
/// 一屏时滚到顶就停(不留空白),摘要行只上移到顶上内容允许的位置。
class TurnProcess extends StatefulWidget {
  const TurnProcess({
    super.key,
    required this.row,
    required this.buildRow,
    required this.gapBefore,
    this.settle = false,
  });

  final TurnProcessRow row;

  /// 这一轮刚在眼前结束:先按展开的样子出现,再收成摘要。
  final bool settle;

  /// 展开后每一行怎么画(就是列表自己的行)。
  final Widget Function(ChatRow row) buildRow;

  /// 行与行之间的间距(列表的规则)。
  final double Function(ChatRow row, ChatRow? previous) gapBefore;

  @override
  State<TurnProcess> createState() => _TurnProcessState();
}

/// 这一轮的状态:文字和图标**只认这一个判定**,避免两套规则各自为政
/// (曾经文字写「已中断」、左边的图标却落到 ✓)。
enum TurnProcessKind { running, completed, interrupted, error, stopped }

/// 唯一的轮次状态判定:运行中、完成、被中断、出错、已停止。
///
/// 中断 / 停止 / 出错都排在「完成」前面;任何被停下、被停止或被插话切开
/// 的轮都不会再渲染成 ✓。
TurnProcessKind turnProcessKind(TurnProcessRow row) {
  if (row.running) return TurnProcessKind.running;
  final reason = row.trace.endReason;
  // 被插话切开的那一片已经结束，不该再自称思考中。
  if (row.cut != null && reason == null) return TurnProcessKind.interrupted;
  if (reason == null) return TurnProcessKind.completed;
  if (reason.isAborted || reason.kind == 'interrupted') return TurnProcessKind.interrupted;
  // 「出错」是状态词,比 l10n 里的「错误」更贴这一行;英文仍是 Error。
  if (reason.isError) return TurnProcessKind.error;
  if (reason.isCompleted) return TurnProcessKind.completed;
  return TurnProcessKind.stopped;
}

/// 这一轮的状态词:跑着 `思考中…`、正常结束 `已完成`、被停下 `已中断`、
/// 出错 `出错`,其余(拦截、超长、崩溃收尾)`已停止`。
String turnProcessStatus(TurnProcessRow row) {
  final l10n = appL10n;
  return switch (turnProcessKind(row)) {
    TurnProcessKind.running => '${l10n.chatThinkingActive}…',
    TurnProcessKind.completed => l10n.commonCompleted,
    TurnProcessKind.interrupted => l10n.chatInterrupted,
    TurnProcessKind.error => AppLocale.isEnglish ? l10n.chatError : '出错',
    TurnProcessKind.stopped => l10n.commonStopped,
  };
}

/// 摘要:`思考中… · 3 步` / `已完成 · 7 步`;一步都没有、只有几段思考时
/// `已完成 · 3 段思考`。
String turnProcessLabel(TurnProcessRow row) {
  final thoughts = row.rows.whereType<AssistantRow>().where((r) => r.item.reasoning.trim().isNotEmpty).length;
  final steps = row.toolCalls + row.messages;
  return [
    turnProcessStatus(row),
    if (steps > 0) appL10n.chatTurnSteps(steps),
    if (steps == 0 && thoughts > 0) appL10n.chatTurnThoughts(thoughts),
  ].join(' · ');
}

class _TurnProcessState extends State<TurnProcess> with SingleTickerProviderStateMixin {
  late final AnimationController _reveal = AnimationController(vsync: this, duration: AppMotion.collapse)
    ..addListener(_holdHeader)
    ..addStatusListener((status) {
      // 收完了:把内容从树上拿掉。
      if (status == AnimationStatus.dismissed && mounted) setState(() {});
    });
  late final CurvedAnimation _factor = CurvedAnimation(parent: _reveal, curve: AppMotion.collapseCurve);
  late final Animation<double> _summaryFactor = ReverseAnimation(_factor);

  // 展开时先让出空间再淡入,收起时先淡出再收高度(同 ExpandReveal)。
  late final CurvedAnimation _fade = CurvedAnimation(
    parent: _reveal,
    curve: const Interval(0.3, 1, curve: Curves.easeOut),
  );
  final _body = GlobalKey();

  /// 已经补进滚动位置的高度(内容在动画途中还可能变高,按高度记账)。
  double _held = 0;

  /// Automatic completion follows ChatList's content boundary, without the
  /// title compensation used when the person manually expands the process.
  late bool _settling = widget.settle;

  bool get _open => _reveal.status == AnimationStatus.forward || _reveal.status == AnimationStatus.completed;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reveal.duration = AppMotion.of(context, _settling ? AppMotion.standard : AppMotion.collapse);
    if (!_settling || !_reveal.isDismissed) return;
    if (_reveal.duration == Duration.zero) {
      _settling = false;
      return;
    }
    // Match the reply action row's reveal: the old process shrinks while
    // its summary and the actions grow by the same progress. Start in this
    // frame, too; a post-frame start would leave the actions one frame ahead.
    _factor.reverseCurve = AppMotion.std.flipped;
    _reveal.value = 1;
    _reveal.reverse().whenCompleteOrCancel(() {
      if (!mounted) return;
      _settling = false;
      _factor.reverseCurve = null;
      _reveal.duration = AppMotion.of(context, AppMotion.collapse);
    });
  }

  @override
  void dispose() {
    _fade.dispose();
    _factor.dispose();
    _reveal.dispose();
    super.dispose();
  }

  void _toggle() {
    if (_open) {
      _reveal.reverse();
    } else {
      _reveal.forward();
    }
    setState(() {});
  }

  /// 标题前的状态图形。状态只认 [turnProcessKind],和文字同一套判定:
  /// 跑着是静止的三颗圆点(不转圈)、结束 ✓、被停 ⊘、出错 ⚠、其余 ⏹。
  /// 颜色跟着状态走(出错用危险色,其余是过程行的三级色)。
  Widget _statusGlyph(BuildContext context) {
    final colors = DshColors.of(context);
    return switch (turnProcessKind(widget.row)) {
      TurnProcessKind.running => QuietDots(color: colors.textTertiary),
      TurnProcessKind.completed => Icon(AppIcons.completed, size: 18, color: colors.textTertiary),
      TurnProcessKind.error => Icon(AppIcons.error, size: 18, color: colors.danger),
      TurnProcessKind.interrupted => Icon(AppIcons.blocked, size: 18, color: colors.textTertiary),
      TurnProcessKind.stopped => Icon(AppIcons.stopped, size: 18, color: colors.textTertiary),
    };
  }

  /// 动画的每一帧(布局之前):在往上长的那段里,把长出来的高度从滚动
  /// 位置里扣掉。内容高度要等第一次布局后才知道,那之前先记着,帧后再补。
  void _holdHeader() {
    if (!mounted || _settling) return;
    final box = _body.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) {
      if (_factor.value > 0) {
        SchedulerBinding.instance.addPostFrameCallback((_) => _holdHeader());
      }
      return;
    }
    final shown = _factor.value * box.size.height;
    final delta = shown - _held;
    if (delta.abs() < 0.01) return;
    _held = shown;
    final sliver = context.findAncestorRenderObjectOfType<RenderSliver>();
    if (sliver == null || sliver.constraints.growthDirection != GrowthDirection.reverse) {
      return;
    }
    final position = Scrollable.maybeOf(context)?.position;
    if (position == null || !position.hasPixels) return;
    position.jumpTo(position.pixels - delta);
  }

  @override
  Widget build(BuildContext context) {
    final label = turnProcessLabel(widget.row);
    final rows = widget.row.rows;
    final open = _open;

    final header = Semantics(
      button: true,
      expanded: open,
      label: label,
      excludeSemantics: true,
      onTap: _toggle,
      child: StreamRowFrame(
        onTap: _toggle,
        child: Row(
          children: [
            StreamLead(child: _statusGlyph(context)),
            const SizedBox(width: StreamRow.gap),
            Expanded(
              child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: StreamRow.label(context)),
            ),
            const SizedBox(width: 4),
            StreamChevron(open: open),
          ],
        ),
      ),
    );

    Widget body() => Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < rows.length; i++)
          Padding(
            key: ValueKey(rows[i].key),
            padding: EdgeInsets.only(top: i == 0 ? 0 : widget.gapBefore(rows[i], rows[i - 1])),
            child: widget.buildRow(rows[i]),
          ),
      ],
    );
    if (_settling) {
      // Keep the running rows' width and spacing until they disappear. Adding
      // the expanded thread's indent/padding would change their height at t=0.
      // The emerging summary becomes interactive when this brief handoff ends.
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          SizeTransition(
            sizeFactor: _summaryFactor,
            alignment: AlignmentDirectional.topCenter,
            child: FadeTransition(
              opacity: _summaryFactor,
              child: IgnorePointer(child: header),
            ),
          ),
          SizeTransition(
            sizeFactor: _factor,
            alignment: AlignmentDirectional.topCenter,
            child: FadeTransition(
              opacity: _fade,
              child: KeyedSubtree(key: _body, child: body()),
            ),
          ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        header,
        if (_reveal.status != AnimationStatus.dismissed)
          SizeTransition(
            sizeFactor: _factor,
            alignment: AlignmentDirectional.topCenter,
            child: FadeTransition(
              opacity: _fade,
              child: Padding(
                key: _body,
                padding: const EdgeInsets.only(top: 4, bottom: 4),
                child: StreamThread(child: body()),
              ),
            ),
          ),
      ],
    );
  }
}
