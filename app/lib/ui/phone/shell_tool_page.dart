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
/// (kind, 标题, 说明) — the seven switches under 拦截危险命令.
const _categories = <(String, String, String)>[
  ('uninstall', '卸载 / 清数据', 'pm uninstall、pm clear、pm disable-user、pm revoke'),
  ('security', '改安全设置', 'settings put secure/global、停用系统组件、伪造开机广播'),
  ('files', '删文件', 'rm -rf 系统或数据目录、mkfs、dd 写块设备、wipe'),
  ('power', '关机 / 提权', 'reboot、su'),
  ('camera', '摄像头', 'appops set … CAMERA、pm grant/revoke …CAMERA、service call … camera'),
  ('microphone', '麦克风', 'appops set … RECORD_AUDIO、pm grant/revoke …RECORD_AUDIO、service call … audio'),
  ('location', '位置', 'appops set … LOCATION、pm grant/revoke …LOCATION'),
];

class ShellToolPage extends ConsumerWidget {
  const ShellToolPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = ref.watch(phoneProvider).status;
    final allowed = status.shellAllowed;
    final guard = status.shellGuard;
    final ask = status.shellAskBeforeRun;
    final identity = status.background.shell;
    final channel = status.background.mode.name;
    final theme = Theme.of(context);
    final notifier = ref.read(phoneProvider.notifier);

    return AppPage.list(
      title: '模型 shell 工具',
      subtitle: const Text('让 Agent 以 shell 身份在手机上执行命令'),
      children: [
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          children: [
            ListPageRow(
              icon: Icons.terminal,
              title: '允许模型使用 shell 工具',
              subtitle: allowed ? '已允许：模型可以执行 dumpsys / pm / settings 这类命令' : '已禁止：模型拿不到 shell',
              subtitleMaxLines: 3,
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
          children: [
            ListPageRow(
              icon: Icons.shield_outlined,
              title: '拦截危险命令',
              subtitle: guard ? '开启：下面命中的类别会被直接拒绝' : '已关闭：下面这些命令可以执行，风险自负',
              subtitleMaxLines: 3,
              onTap: () => unawaited(notifier.setShellGuard(!guard)),
              trailing: Switch(
                value: guard,
                onChanged: (v) => unawaited(notifier.setShellGuard(v)),
              ),
            ),
          ],
        ),
        // 分开关常显：拦截关闭时置灰，让「拦截开 + 类别关 = 放行」这条规则看得见。
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '拦截哪几类',
          children: [
            for (final (kind, label, hint) in _categories)
              ListPageRow(
                title: label,
                subtitle: hint,
                subtitleMaxLines: 3,
                onTap: guard
                    ? () => unawaited(
                          notifier.setShellGuardCategory(kind, !(status.guardCategories[kind] ?? true)),
                        )
                    : null,
                trailing: Switch(
                  value: status.guardCategories[kind] ?? true,
                  onChanged: guard
                      ? (v) => unawaited(notifier.setShellGuardCategory(kind, v))
                      : null,
                ),
              ),
            if (!guard)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                child: Text(
                  '拦截已关闭，这七个开关暂时无效。',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          children: [
            ListPageRow(
              icon: Icons.help_outline,
              title: '执行前询问',
              // 拦截优先：开着拦截时命中就直接拒绝，不会先问一次。
              subtitle: guard
                  ? '拦截已开启：命中类别将直接拒绝，不会询问'
                  : '只对上面命中的命令询问，其余直接执行',
              subtitleMaxLines: 3,
              onTap: guard ? null : () => unawaited(notifier.setShellAskBeforeRun(!ask)),
              trailing: Switch(
                // 置灰但仍显示真实值：关掉拦截后这个开关立刻恢复原来的选择。
                value: ask,
                onChanged: guard ? null : (v) => unawaited(notifier.setShellAskBeforeRun(v)),
              ),
            ),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '当前身份',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 2),
              child: Text(
                '当前身份：${identity?.label ?? '未连接'}',
                style: theme.textTheme.titleSmall,
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Text(
                status.background.ready
                    ? '特权通道（$channel）已就绪，命令会以这个 UID 执行。'
                    : '特权通道未就绪：${status.background.reason ?? '未连接'}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  height: 1.4,
                ),
              ),
            ),
            CheckRow(label: '本页开关已允许', done: allowed),
          ],
        ),
        ListPageCard(
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          title: '说明',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: Text(
                'shell 身份来自特权通道（Shizuku / Sui / root / 自连 ADB），所以通道关闭时本开关不会有任何效果。\n'
                '单条命令默认 30 秒超时，最长 300 秒。输出不截断：超过约 26 万字符时，完整内容会写入临时文件并把路径返回。',
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
