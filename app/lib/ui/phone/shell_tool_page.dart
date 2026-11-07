// 模型 shell 工具: what the model may run on this phone, what it may not, and the
// one switch that allows it. The tool rides 特权通道 (shell uid) but has its own
// consent, so opening the channel never silently hands the model a shell.

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

/// The commands the core refuses before they reach the channel (mirrors
/// `PhoneCore.SHELL_DENY`; listed here so the page is honest about it).
const _refused = <String>[
  '卸载 / 清数据（pm uninstall、pm clear）',
  '改安全设置（device_policies、锁屏、ADB 开关）',
  '删除文件（rm -rf、mv 到 /dev/null）',
  '关机 / 重启 / 恢复出厂',
];

class ShellToolPage extends ConsumerWidget {
  const ShellToolPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(phoneProvider).status;
    final allowed = status.shellAllowed;
    final channel = status.background.mode.name;
    final ready = status.background.ready;
    final theme = Theme.of(context);
    final notifier = ref.read(phoneProvider.notifier);

    return AppPage.list(
      title: '模型 shell 工具',
      subtitle: const Text('让 Agent 以 shell 身份在手机上执行只读命令'),
      children: [
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          children: [
            ListPageRow(
              icon: Icons.terminal,
              title: '允许模型使用 shell 工具',
              subtitle: allowed ? '已允许：模型可以执行 dumpsys / pm / settings 这类命令' : '已禁止：模型拿不到 shell',
              onTap: () => unawaited(notifier.setShellAllowed(!allowed)),
              trailing: Switch(
                value: allowed,
                onChanged: (v) => unawaited(notifier.setShellAllowed(v)),
              ),
            ),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '运行条件',
          children: [
            CheckRow(label: '特权通道已开启（当前：$channel）', done: ready),
            CheckRow(label: '本页开关已允许', done: allowed),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '会被直接拒绝的命令',
          children: [
            for (final line in _refused)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: Text('· $line'),
              ),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '说明',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Text(
                'shell 身份来自特权通道（Shizuku / Shevery / Dhizuku / 自连 ADB），'
                '所以通道关闭时本开关不会有任何效果。\n'
                '单条命令默认 30 秒超时，输出超过 64KB 会被截断。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.5,
                ),
              ),
            ),
          ],
        ),
      ],
    );
  }
}
