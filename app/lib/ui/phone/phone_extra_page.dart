import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../state/keep_alive_prefs.dart';
import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'dnd_mode_page.dart';
import 'privileged_channel_page.dart';
import 'shell_tool_page.dart';

/// 操控通道与工具: 特权通道、虚拟屏、模型 shell 工具。
///
/// 三项都是「怎么跑」而不是「怎么用手机」，所以从「手机操控」里分出来，
/// 在设置里占一个自己的入口。
class PhoneExtraPage extends ConsumerWidget {
  const PhoneExtraPage({super.key});

  void _push(BuildContext context, Widget page) =>
      Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(phoneProvider.select((s) => s.status));
    final phone = ref.read(phoneProvider.notifier);
    final bg = status.background;
    final keep = ref.watch(keepAlivePrefsProvider);
    final channelReady = bg.mode != PhoneBackgroundMode.off && bg.ready;
    return AppPage.list(
      title: '操控通道与工具',
      children: [
        ListPageCard(
          title: '运行通道',
          children: [
            ListPageRow(
              icon: AppIcons.systemAccess,
              title: '特权通道',
              subtitle: privilegedSummary(bg),
              onTap: () => _push(context, const PhonePrivilegedPage()),
            ),
          ],
        ),
        ListPageCard(
          title: '保活',
          children: [
            ListPageRow(
              icon: AppIcons.keepAlive,
              title: '保持后台运行',
              subtitle: !channelReady
                  ? '特权通道未就绪：开关已记住，通道开启后生效'
                  : keep.keepAliveEnabled
                  ? '前台常驻 + 系统白名单 + shell 守护进程'
                  : '已关闭：撤销白名单并停止守护进程',
              onTap: channelReady ? () => unawaited(ref.read(keepAlivePrefsProvider.notifier).setEnabled(!keep.keepAliveEnabled)) : null,
              trailing: Switch(
                value: keep.keepAliveEnabled,
                onChanged: channelReady ? (v) => unawaited(ref.read(keepAlivePrefsProvider.notifier).setEnabled(v)) : null,
              ),
            ),
            ListPageRow(
              icon: AppIcons.power,
              title: '保活时省电',
              subtitle: '延长守护间隔、空闲时短时续租，代价是深度休眠后恢复稍慢',
              onTap: channelReady && keep.keepAliveEnabled
                  ? () => unawaited(ref.read(keepAlivePrefsProvider.notifier).setEconomy(!keep.keepAliveEconomy))
                  : null,
              trailing: Switch(
                value: keep.keepAliveEconomy,
                onChanged: channelReady && keep.keepAliveEnabled
                    ? (v) => unawaited(ref.read(keepAlivePrefsProvider.notifier).setEconomy(v))
                    : null,
              ),
            ),
            if (keep.watchdogRunning)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                child: Text(
                  '已通过特权通道加入系统白名单并启动守护进程（每 ${keep.watchSeconds} 秒检查一次引擎）',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    height: 1.5,
                  ),
                ),
              ),
          ],
        ),
        ListPageCard(
          title: '息屏保活（ColorOS）',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Text(
                'ColorOS 需要依次设置：\n'
                '1. 设置 → 电池 → 应用耗电管理 → DSH Go → 允许后台运行\n'
                '2. 应用 → 自启动 → 打开\n'
                '3. 最近任务卡片下拉锁定',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  height: 1.6,
                ),
              ),
            ),
          ],
        ),
        ListPageCard(
          title: '工具',
          children: [
            ListPageRow(
              icon: AppIcons.dndMode,
              title: '虚拟屏',
              subtitle: bg.vdisplayEnabled ? dndSummary(bg) : '已关闭',
              onTap: () => _push(context, const PhoneDndPage()),
              trailing: Switch(
                value: bg.vdisplayEnabled,
                onChanged: (v) => unawaited(phone.setVdisplayEnabled(v)),
              ),
            ),
            ListPageRow(
              icon: Icons.terminal,
              title: '模型 shell 工具',
              subtitle: status.shellAllowed ? '已允许：可执行 dumpsys / pm / settings' : '已禁止：模型拿不到 shell',
              onTap: () => _push(context, const ShellToolPage()),
              trailing: Switch(
                value: status.shellAllowed,
                onChanged: (v) => unawaited(phone.setShellAllowed(v)),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
