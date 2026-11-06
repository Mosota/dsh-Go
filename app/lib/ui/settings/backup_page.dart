// 备份与恢复: the user's DSH home — sessions, settings, skills and plugins —
// packed into one zip and shared out, or restored from a zip. The API key never
// leaves the device (Kotlin BackupManager excludes .env and other secrets).

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:share_plus/share_plus.dart';

import '../../runtime/runtime_state.dart';
import '../shell/page_frame.dart';
import 'settings_widgets.dart';

class BackupPage extends ConsumerStatefulWidget {
  const BackupPage({super.key});

  @override
  ConsumerState<BackupPage> createState() => _BackupPageState();
}

class _BackupPageState extends ConsumerState<BackupPage> {
  bool _busy = false;

  Future<void> _backup() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final path = await ref.read(runtimeBridgeProvider).backup();
      if (!mounted) return;
      if (path == null) {
        _say('备份失败：运行环境尚未就绪');
      } else {
        await SharePlus.instance.share(
          ShareParams(files: [XFile(path, mimeType: 'application/zip')], subject: 'DSH Go 备份'),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _restore() async {
    if (_busy) return;
    final file = await FilePicker.pickFile(
      type: FileType.custom,
      allowedExtensions: const ['zip'],
    );
    final path = file?.path;
    if (path == null || !mounted) return;
    setState(() => _busy = true);
    try {
      final ok = await ref.read(runtimeBridgeProvider).restore(path);
      if (!mounted) return;
      _say(ok ? '恢复成功，建议重启引擎后查看' : '恢复失败：备份文件无效或无法写入');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String text) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  @override
  Widget build(BuildContext context) {
    return AppPage.list(
      title: '备份与恢复',
      children: [
        SettingsSection(
          title: '备份',
          children: [
            SettingsTile(
              icon: Icons.archive_outlined,
              title: '备份会话与设置',
              subtitle: '打包会话、模型设置、插件与技能，不含 API key',
              onTap: _busy ? null : _backup,
            ),
          ],
        ),
        SettingsSection(
          title: '恢复',
          footer: const Text('导入会覆盖同名数据，zip 里没有的内容保留不动。'),
          children: [
            SettingsTile(
              icon: Icons.restore,
              title: '从备份导入',
              subtitle: '选择之前导出的备份 zip',
              onTap: _busy ? null : _restore,
            ),
          ],
        ),
      ],
    );
  }
}
