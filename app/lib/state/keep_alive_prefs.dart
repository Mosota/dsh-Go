import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../phone/phone_channel.dart';
import 'phone_state.dart';

/// 设置 → 操控通道与工具 → 保活, and 设置 → 性能 → 全局省电. The native side owns the
/// three keys (DshService, the shell watchdog and the frame poll read them there), so the
/// page shows a change at once and then takes the native answer.
class KeepAlivePrefsController extends Notifier<PhoneKeepAlivePrefs> {
  late Future<void> _loaded;

  PhoneChannel get _channel => ref.read(phoneChannelProvider);

  @override
  PhoneKeepAlivePrefs build() {
    _loaded = _load();
    return const PhoneKeepAlivePrefs();
  }

  Future<void> _load() async {
    final stored = await _channel.keepAlivePrefs();
    if (stored != null && ref.mounted) state = stored;
  }

  Future<void> setEnabled(bool enabled) =>
      _save(state.copyWith(keepAliveEnabled: enabled, keepAliveEconomy: state.keepAliveEconomy));

  Future<void> setEconomy(bool economy) => _save(state.copyWith(keepAliveEconomy: economy));

  Future<void> setGlobalPowerSaver(bool saver) => _save(state.copyWith(globalPowerSaver: saver));

  Future<void> _save(PhoneKeepAlivePrefs next) async {
    await _loaded;
    if (!ref.mounted || next == state) return;
    state = next;
    final stored = await _channel.setKeepAlivePrefs(
      keepAliveEnabled: next.keepAliveEnabled,
      keepAliveEconomy: next.keepAliveEconomy,
      globalPowerSaver: next.globalPowerSaver,
    );
    if (stored != null && ref.mounted) state = stored;
  }
}

final keepAlivePrefsProvider = NotifierProvider<KeepAlivePrefsController, PhoneKeepAlivePrefs>(
  KeepAlivePrefsController.new,
);
