import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';
import 'privileged_channel_page.dart';

/// Shizuku's own download page (official site).
const shizukuDownloadUrl = 'https://shizuku.rikka.app/download/';

extension PhoneChannelLabel on PhonePrivilegedMode {
  /// What 虚拟屏 calls this channel.
  String label(L10n l10n) => switch (this) {
    PhonePrivilegedMode.off => l10n.phoneDisabled,
    PhonePrivilegedMode.shizuku => 'Shizuku',
    PhonePrivilegedMode.sui => l10n.phonePrivilegedSui,
    PhonePrivilegedMode.root => l10n.phonePrivilegedRoot,
    PhonePrivilegedMode.adb => l10n.phoneDndDirectAdb,
  };
}

/// One-line state of 虚拟屏 for the row on the main page.
String dndSummary(PhoneBackgroundStatus bg) {
  final l10n = appL10n;
  if (!bg.androidOk) return l10n.phoneDndRequiresAndroid;
  final channel = bg.effectiveMode.label(l10n);
  return switch (bg.effectiveMode) {
    PhonePrivilegedMode.off => l10n.phoneDndHiddenScreen,
    _ when bg.effectiveReady => l10n.phoneDndChannelReady(channel),
    _ => l10n.phoneDndChannelStatus(channel, bg.effectiveReason ?? l10n.phoneNotReadyYet),
  };
}

/// One-line state of the 特权通道 row: the channel both 虚拟屏 and the model's
/// `shell` tool run on.
String privilegedSummary(PhoneBackgroundStatus bg) {
  if (!bg.androidOk) return appL10n.phoneDndRequiresAndroid;
  final mode = bg.effectiveMode;
  if (mode == PhonePrivilegedMode.off) return '未开启';
  final label = mode.label(appL10n);
  return bg.effectiveReady ? '$label · 已就绪' : '$label · ${bg.effectiveReason ?? appL10n.phoneNotReadyYet}';
}

const _groupMargin = EdgeInsets.fromLTRB(16, 12, 16, 12);

/// Opens or closes 悬浮窗查看虚拟屏. Without 显示在其他应用上层 the window can't be added: the
/// native side already opened the system switch, and this says what to turn on there.
Future<void> _toggleVscreenOverlay(BuildContext context, WidgetRef ref, bool open) async {
  final phone = ref.read(phoneProvider.notifier);
  final result = open ? await phone.openVscreenOverlay() : await phone.closeVscreenOverlay();
  if (!context.mounted) return;
  if (!open || result.ok) return;
  final text = result.reasonCode == 'noOverlayPermission'
      ? '请允许显示在其他应用上层'
      : (result.reason ?? '悬浮窗打不开');
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
}

/// 虚拟屏: the agent works on a virtual display, on the 特权通道. The channel
/// itself is chosen on [PhonePrivilegedPage]; this page only says what the mode
/// does and whether the channel is there yet.
class PhoneDndPage extends ConsumerWidget {
  const PhoneDndPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final bg = ref.watch(phoneProvider.select((s) => s.status.background));
    final overlay = ref.watch(vscreenOverlayProvider);
    // The real channel mode: the legacy `mode` reports a root channel as off.
    final ready = bg.effectiveMode != PhonePrivilegedMode.off && bg.effectiveReady;

    return AppPage.list(
      title: '虚拟屏',
      subtitle: const Text('Agent 在一块看不见的屏幕上操作'),
      children: [
        if (!bg.androidOk) PhoneNote(tone: NoteTone.warn, text: l10n.phoneDndUnsupported),
        ListPageCard(
          margin: _groupMargin,
          children: [
            ListPageRow(
              icon: AppIcons.dndMode,
              title: '虚拟屏',
              subtitle: bg.vdisplayEnabled ? '开启：Agent 在虚拟屏上操作，不占用你的屏幕' : '已关闭：Agent 不再使用虚拟屏',
              onTap: () => unawaited(ref.read(phoneProvider.notifier).setVdisplayEnabled(!bg.vdisplayEnabled)),
              trailing: Switch(
                value: bg.vdisplayEnabled,
                onChanged: (v) => unawaited(ref.read(phoneProvider.notifier).setVdisplayEnabled(v)),
              ),
            ),
            ListPageRow(
              icon: AppIcons.dndMode,
              title: '悬浮窗查看虚拟屏',
              subtitle: '在任意界面浮一个小窗，能看也能点',
              onTap: () => unawaited(_toggleVscreenOverlay(context, ref, !overlay)),
              trailing: Switch(
                value: overlay,
                onChanged: (v) => unawaited(_toggleVscreenOverlay(context, ref, v)),
              ),
            ),
          ],
        ),
        if (bg.vdisplayEnabled && !ready)
          PhoneNote(tone: NoteTone.warn, text: '特权通道还没就绪，这个模式暂时用不了。'),
        ListPageCard(
          margin: _groupMargin,
          title: '运行通道',
          children: [
            ListPageRow(
              icon: AppIcons.dndMode,
              iconColor: ready ? Theme.of(context).colorScheme.primary : null,
              title: '特权通道',
              subtitle: privilegedSummary(bg),
              onTap: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const PhonePrivilegedPage()),
              ),
            ),
          ],
        ),
        ListPageCard(
          margin: _groupMargin,
          title: '这个模式做什么',
          children: [
            BulletLines(['在虚拟屏上打开应用、点击和输入，不占用你正在看的屏幕', '需要上面那条特权通道已就绪']),
          ],
        ),
        ListPageCard(
          margin: _groupMargin,
          title: l10n.phoneDndRequirements,
          children: [
            BulletLines([l10n.phoneDndKeepUnlocked, l10n.phoneDndReactivate, l10n.phoneDndKeepDebugging]),
          ],
        ),
        if (!ready)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: FilledButton(
              style: FilledButton.styleFrom(minimumSize: const Size.fromHeight(48)),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(builder: (_) => const PhonePrivilegedPage()),
              ),
              child: const Text('去开启特权通道'),
            ),
          ),
      ],
    );
  }
}
