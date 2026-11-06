// 省电模式: adaptive keep-alive. Idle → let the system sleep (the foreground
// notification and the wake lock are released); a phone task or a waiting
// confirmation → keep-alive comes straight back. Same idea as DSHA's 省电模式.

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../runtime/runtime_state.dart';
import '../shell/page_frame.dart';
import 'settings_widgets.dart';

/// (enabled, busy, keepAlive) as the native side reports it.
class PowerSaveState {
  const PowerSaveState({this.enabled = false, this.busy = false, this.keepAlive = true});

  factory PowerSaveState.fromMap(Map<Object?, Object?>? m) => m == null
      ? const PowerSaveState()
      : PowerSaveState(
          enabled: m['enabled'] == true,
          busy: m['busy'] == true,
          keepAlive: m['keepAlive'] != false,
        );

  final bool enabled;

  /// Something needs the phone right now (a task or a confirmation).
  final bool busy;

  /// Whether the engine is being held awake at this moment.
  final bool keepAlive;

  /// The one line under the switch, in DSHA's words.
  String get line {
    if (!enabled) return '关闭：一直保持后台运行，随时可用';
    return busy ? '省电模式 · 有任务或状态待确认，继续保活' : '省电模式 · 已空闲，允许系统休眠';
  }
}

final powerSaveStateProvider = FutureProvider.autoDispose<PowerSaveState>((ref) async {
  final bridge = ref.watch(runtimeBridgeProvider);
  return PowerSaveState.fromMap(await bridge.powerSave());
});

class PowerSavePage extends ConsumerWidget {
  const PowerSavePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(powerSaveStateProvider).value ?? const PowerSaveState();
    final theme = Theme.of(context);
    return AppPage.list(
      title: '省电模式',
      subtitle: const Text('没人用的时候让系统睡，有事立刻醒来'),
      children: [
        SettingsSection(
          children: [
            SettingsTile(
              icon: Icons.battery_saver,
              title: '省电模式',
              subtitle: state.line,
              onTap: () => unawaited(_set(ref, !state.enabled)),
              trailing: Switch(value: state.enabled, onChanged: (v) => unawaited(_set(ref, v))),
            ),
          ],
        ),
        SettingsSection(
          title: '开启后',
          children: [
            for (final line in const [
              '空闲时释放常驻通知和唤醒锁，允许系统进入休眠',
              '有手机任务在跑、或有确认等你点的时候，自动恢复保活',
              '引擎和会话不丢；只是系统可以回收后台',
            ])
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: Text('· $line'),
              ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 4, 24, 0),
          child: Text(
            '代价：空闲久了，系统可能把这个 App 的后台一起回收，回来时要等引擎重新启动。',
            style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }

  Future<void> _set(WidgetRef ref, bool on) async {
    await ref.read(runtimeBridgeProvider).setPowerSave(on);
    ref.invalidate(powerSaveStateProvider);
  }
}
