// 运行中的轻量指示：三颗渐隐圆点，静止不动。

import 'package:flutter/widgets.dart';

/// 三颗从左到右渐隐的小圆点，表示「这一步还在跑」。
///
/// 用它替掉 [CircularProgressIndicator] / 鲸尾：两者都在一直动，收起的
/// 过程行在眼角角落转个不停，比信息本身更抢眼。这里只画一次、不驱动任何
/// 动画帧；[level] 沿用速度档（0 = 等你，4 = 飞快），只改浓淡，不做动画。
class QuietDots extends StatelessWidget {
  const QuietDots({super.key, required this.color, this.size = 18, this.level = 1});

  final Color color;

  /// 整个图形的方形边长（与 [StreamLead] 的槽位一致）。
  final double size;

  /// 0–4 的速度档，只影响不透明度。
  final int level;

  static const _dot = 3.4;
  static const _gap = 2.2;

  @override
  Widget build(BuildContext context) {
    final base = 0.5 + 0.1 * level.clamp(0, 4).toDouble();
    return SizedBox.square(
      dimension: size,
      child: Center(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (var i = 0; i < 3; i++) ...[
              if (i > 0) const SizedBox(width: _gap),
              DecoratedBox(
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: color.withValues(alpha: (base - i * 0.2).clamp(0.12, 1.0).toDouble()),
                ),
                child: const SizedBox.square(dimension: _dot),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
