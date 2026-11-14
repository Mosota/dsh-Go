// About DSH Go Plus: app information and upstream-update checks, the original
// author folded into one row (GitHub only), then the brand mark, slogan and
// unofficial-app disclaimer.

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/app_info.dart';
import '../../state/star_prompt.dart' show starLinkOpenerProvider;
import '../../state/update_state.dart';
import '../common/expand_reveal.dart';
import '../common/row_marks.dart';
import '../shell/brand_mark.dart';
import '../shell/page_frame.dart';
import '../star/star_prompt_dialog.dart' show openStarProject;
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import '../update/update_tiles.dart';
import 'settings_widgets.dart';

/// The installed version as the app shows it: `0.9.6 (10271234)`.
String installedVersionLabel(WidgetRef ref) {
  // What Android reports as installed (the compiled-in constant until it does).
  final installed = ref.watch(appUpdateProvider.select((s) => s.current));
  return installed.build == null ? installed.name : '${installed.name} (${installed.build})';
}

class AboutPage extends ConsumerWidget {
  const AboutPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    final version = installedVersionLabel(ref);
    return AppPage.list(
      // 标题跟着应用名走:关于 DSH Go Plus(不再用 l10n 里写死的「关于 DSH Go」)。
      title: AppLocale.isEnglish ? 'About ${AppInfo.name}' : '关于 ${AppInfo.name}',
      children: [
        SettingsSection(
          children: [
            SettingsTile(
              spot: Spots.version,
              value: Text(version, style: AppType.tabular(theme.textTheme.bodyMedium!)),
            ),
            // 检查上游更新(原来挂在「原作者」区里,折叠后会一起藏起来)。
            const AppUpdateTiles(),
            _authorLink(
              context,
              ref,
              'about-author-home',
              '维护与改进',
              'https://github.com/Mosota',
              'https://github.com/Mosota',
            ),
            SettingsTile(
              spot: Spots.licenses,
              onTap: () => showLicensePage(
                context: context,
                applicationName: AppInfo.name,
                applicationVersion: version,
                applicationIcon: const Padding(padding: EdgeInsets.all(12), child: BrandMark(size: 56, tile: true)),
                applicationLegalese: AppInfo.disclaimer,
              ),
            ),
          ],
        ),
        // 「DSH Go 原作者」整块收成一行小选项,点开才看到原作者的主页链接。
        // 作者名、B站、X 已按要求删掉,只留 GitHub。
        _AuthorFold(
          title: l10n.hubAboutAuthor,
          child: _authorLink(
            context,
            ref,
            'about-author-github',
            l10n.hubAboutGithub,
            '@${AppInfo.author}',
            AppInfo.authorGithubUrl,
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 0),
          child: Column(
            children: [
              BrandMark(size: 18, color: colors.textDisabled),
              const SizedBox(height: 10),
              Text(
                AppInfo.slogan,
                textAlign: TextAlign.center,
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
              ),
              const SizedBox(height: 8),
              Text(
                AppInfo.disclaimer,
                textAlign: TextAlign.center,
                style: theme.textTheme.labelSmall?.copyWith(color: colors.textTertiary),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _authorLink(BuildContext context, WidgetRef ref, String key, String title, String handle, String url) =>
      Semantics(
        link: true,
        child: SettingsTile(
          key: ValueKey(key),
          title: title,
          subtitle: handle,
          trailing: Icon(AppIcons.openExternal, size: 18, color: DshColors.of(context).textTertiary),
          onTap: () => unawaited(_openAuthorLink(context, ref, Uri.parse(url))),
        ),
      );

  Future<void> _openAuthorLink(BuildContext context, WidgetRef ref, Uri url) async {
    final l10n = context.l10n;
    final messenger = ScaffoldMessenger.maybeOf(context);
    try {
      if (await ref.read(starLinkOpenerProvider)(url)) return;
    } catch (_) {
      // As with the project's link, fall back to copying when no browser opens.
    }
    var copied = true;
    try {
      await Clipboard.setData(ClipboardData(text: url.toString()));
    } catch (_) {
      copied = false;
    }
    if (messenger?.mounted ?? false) {
      messenger!.showSnackBar(
        SnackBar(content: Text(copied ? l10n.shellBrowserUrlCopied : l10n.stateBrowserOpenFailed)),
      );
    }
  }
}

/// 「DSH Go 原作者」折叠成的一行小选项:点一下才展开,里面只有原作者
/// 的 GitHub 主页(作者名、B站、X 已按要求删掉)。
///
/// 和设置里其他卡同一套外壳([SettingsSection] + [SettingsTile]),展开/
/// 收起用 [ExpandReveal],箭头用 [RowExpandArrow],视觉和别处一致。
class _AuthorFold extends StatefulWidget {
  const _AuthorFold({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  State<_AuthorFold> createState() => _AuthorFoldState();
}

class _AuthorFoldState extends State<_AuthorFold> {
  bool _open = false;

  @override
  Widget build(BuildContext context) => SettingsSection(
    children: [
      Semantics(
        expanded: _open,
        child: SettingsTile(
          title: widget.title,
          trailing: RowExpandArrow(open: _open),
          onTap: () => setState(() => _open = !_open),
        ),
      ),
      ExpandReveal(open: _open, child: widget.child),
    ],
  );
}
