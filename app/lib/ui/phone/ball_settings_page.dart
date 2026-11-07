import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

const _groupMargin = EdgeInsets.fromLTRB(16, 12, 16, 12);

/// 悬浮球: the ball that floats over other apps while 虚拟屏's picture window is
/// closed, and what it looks like. The settings live on the native side
/// (VscreenBallPrefs.kt); a showing ball follows each change at once.
class PhoneBallSettingsPage extends ConsumerStatefulWidget {
  const PhoneBallSettingsPage({super.key});

  @override
  ConsumerState<PhoneBallSettingsPage> createState() => _PhoneBallSettingsPageState();
}

class _PhoneBallSettingsPageState extends ConsumerState<PhoneBallSettingsPage> {
  /// Null until the native side answers (or forever, without it: the reason the
  /// rows are off).
  PhoneBallPrefs? _prefs;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final prefs = await ref.read(phoneProvider.notifier).ballPrefs();
    if (!mounted || prefs == null) return;
    setState(() => _prefs = prefs);
  }

  Future<void> _setShowing(bool on) async {
    if (_busy) return;
    setState(() => _busy = true);
    final phone = ref.read(phoneProvider.notifier);
    final result = on ? await phone.openBallOverlay() : await phone.closeBallOverlay();
    if (!mounted) return;
    setState(() => _busy = false);
    if (on && !result.ok) {
      final text = result.reasonCode == 'noOverlayPermission' ? '请允许显示在其他应用上层' : (result.reason ?? '悬浮球打不开');
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  /// The page shows the change at once and sends it on, as the 悬浮条's rows do.
  Future<void> _write(PhoneBallPrefs next) async {
    if (next == _prefs) return;
    setState(() => _prefs = next);
    await ref.read(phoneProvider.notifier).setBallPrefs(
      sizeDp: next.sizeDp,
      opacity: next.opacity,
      idleHide: next.idleHide,
      idleMs: next.idleMs,
    );
  }

  @override
  Widget build(BuildContext context) {
    final showing = ref.watch(vscreenBallProvider);
    final prefs = _prefs;

    return AppPage.list(
      title: '悬浮球',
      subtitle: const Text('浮在任意界面上的小球，一点就能看虚拟屏'),
      children: [
        ListPageCard(
          margin: _groupMargin,
          children: [
            MergeSemantics(
              child: ListPageRow(
                icon: AppIcons.overlayBall,
                title: '显示悬浮球',
                subtitle: '需要「显示在其他应用上层」',
                onTap: prefs == null || _busy ? null : () => unawaited(_setShowing(!showing)),
                trailing: Switch(
                  value: showing,
                  onChanged: prefs == null || _busy ? null : (v) => unawaited(_setShowing(v)),
                ),
              ),
            ),
          ],
        ),
        if (prefs != null) ...[
          ListPageCard(
            margin: _groupMargin,
            title: '外观',
            children: [
              _BallSliderRow(
                icon: AppIcons.swap,
                title: '大小',
                label: '${prefs.sizeDp} dp',
                value: prefs.sizeDp.toDouble(),
                min: PhoneBallPrefs.minSizeDp.toDouble(),
                max: PhoneBallPrefs.maxSizeDp.toDouble(),
                step: 4,
                onChanged: (v) => unawaited(_write(prefs.copyWith(sizeDp: v.round()))),
              ),
              _BallSliderRow(
                icon: AppIcons.overlayOpacity,
                title: '不透明度',
                label: '${prefs.percent}%',
                value: prefs.percent.toDouble(),
                min: (PhoneBallPrefs.minOpacity * 100).round().toDouble(),
                max: (PhoneBallPrefs.maxOpacity * 100).round().toDouble(),
                step: 5,
                onChanged: (v) => unawaited(_write(prefs.copyWith(opacity: v / 100))),
              ),
            ],
          ),
          ListPageCard(
            margin: _groupMargin,
            title: '闲置',
            children: [
              MergeSemantics(
                child: ListPageRow(
                  icon: AppIcons.queued,
                  title: '闲置后自动半隐',
                  subtitle: '没有碰它时滑出屏幕一半，再点一下回来',
                  onTap: () => unawaited(_write(prefs.copyWith(idleHide: !prefs.idleHide))),
                  trailing: Switch(
                    value: prefs.idleHide,
                    onChanged: (v) => unawaited(_write(prefs.copyWith(idleHide: v))),
                  ),
                ),
              ),
              _BallSliderRow(
                icon: AppIcons.queued,
                title: '闲置秒数',
                label: '${prefs.idleSeconds} 秒',
                value: prefs.idleSeconds.toDouble(),
                min: (PhoneBallPrefs.minIdleMs / 1000).roundToDouble(),
                max: (PhoneBallPrefs.maxIdleMs / 1000).roundToDouble(),
                step: 1,
                onChanged: prefs.idleHide ? (v) => unawaited(_write(prefs.copyWith(idleMs: v.round() * 1000))) : null,
              ),
            ],
          ),
          const PageFootnote('球总是贴着屏幕的左右边；拖到哪边，它就在哪边停。关掉「显示悬浮球」会把它收起来，虚拟屏本身不受影响。'),
        ] else
          const PageFootnote('原生模块还没就绪，暂时读不到悬浮球的设置。'),
      ],
    );
  }
}

/// 大小 / 不透明度 / 闲置秒数: the row with its value, the slider under the title
/// (from the title's left edge to the value's right one), neutral like every
/// slider in settings. A null [onChanged] greys it out.
class _BallSliderRow extends StatelessWidget {
  const _BallSliderRow({
    super.key,
    required this.icon,
    required this.title,
    required this.label,
    required this.value,
    required this.min,
    required this.max,
    required this.step,
    required this.onChanged,
  });

  final IconData icon;
  final String title;

  /// The value as the row shows it ("56 dp", "80%", "3 秒").
  final String label;
  final double value;
  final double min;
  final double max;
  final int step;
  final ValueChanged<double>? onChanged;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final enabled = onChanged != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        ExcludeSemantics(
          child: ListPageRow(icon: icon, title: title, value: label, iconColor: enabled ? null : scheme.onSurface.withValues(alpha: 0.3)),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(
            ListPageRow.horizontal + ListPageRow.glyphSlot + ListPageRow.gap,
            0,
            ListPageRow.horizontal,
            12,
          ),
          // One node: "大小, 56 dp".
          child: MergeSemantics(
            child: Semantics(
              label: title,
              child: Slider(
                value: value.clamp(min, max).toDouble(),
                min: min,
                max: max,
                divisions: ((max - min) ~/ step).clamp(1, 1000).toInt(),
                padding: EdgeInsets.zero,
                activeColor: enabled ? scheme.onSurfaceVariant : scheme.onSurface.withValues(alpha: 0.2),
                inactiveColor: scheme.onSurface.withValues(alpha: 0.1),
                semanticFormatterCallback: (v) => label,
                onChanged: onChanged == null
                    ? null
                    : (v) {
                        HapticFeedback.selectionClick();
                        onChanged!(v);
                      },
              ),
            ),
          ),
        ),
      ],
    );
  }
}
