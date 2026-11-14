import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
              subtitleMaxLines: 2,
              onTap: () => _push(context, const PhonePrivilegedPage()),
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
              subtitleMaxLines: 2,
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
              subtitleMaxLines: 2,
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
