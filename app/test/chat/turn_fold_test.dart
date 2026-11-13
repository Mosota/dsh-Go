// The whole-turn fold: which turns fold, what goes inside, where the answer,
// footnote and change card end up; plus the thinking preview rules. Over the
// timed fixture (support/run_info_fixture.dart), the captured cancel, and
// small synthetic transcripts for the edge cases.

import 'dart:convert';
import 'dart:io';

import 'package:dsh_app/ui/chat/chat_rows.dart';
import 'package:dsh_app/ui/chat/widgets/thinking_pill.dart';
import 'package:dsh_app/ui/chat/widgets/turn_process.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/run_info_fixture.dart';
import 'support/sample_chat.dart';

final _t0 = DateTime(2026, 10, 23, 14, 30);

TurnTrace trace({
  required int startSeq,
  required int endSeq,
  String reason = 'completed',
  Duration took = const Duration(seconds: 42),
}) => TurnTrace(
  turn: startSeq,
  startSeq: startSeq,
  start: _t0,
  spans: const [],
  endSeq: endSeq,
  end: _t0.add(took),
  endReason: TurnEndReason(reason),
);

List<Type> types(List<ChatRow> rows) => [for (final r in rows) r.runtimeType];

void main() {
  group('the timed fixture', () {
    test('a finished turn folds its process; the answer stays out', () {
      final s = runInfoState(finished: true);
      final rows = deriveRows(items: s.items, turns: s.turns, foldTurns: true);
      expect(types(rows), [UserRow, TurnProcessRow, AssistantRow, ChangeCardRow]);

      final process = rows[1] as TurnProcessRow;
      final tools = s.items.whereType<ToolChatItem>().length;
      expect(process.toolCalls, tools);
      expect(process.trace, same(s.turns.single));
      expect(turnProcessLabel(process), startsWith('已完成 · ${tools + process.messages} 步'));
      // Nothing is lost: every tool call is somewhere inside.
      final inside = [
        for (final r in process.rows)
          ...switch (r) {
            ToolCallRow(:final item) => [item.callId],
            ToolGroupRow(:final tools) => [for (final t in tools) t.callId],
            _ => const <String>[],
          },
      ];
      expect(inside, [for (final t in s.items.whereType<ToolChatItem>()) t.callId]);

      final answer = rows[2] as AssistantRow;
      final last = s.items.whereType<AssistantChatItem>().last;
      expect(answer.item.id, last.id);
      expect(answer.item.text, last.text);
      expect(answer.item.reasoning, isEmpty);
      expect(answer.showActions, isTrue);
      expect(answer.footnote, same(s.turns.single));
      if (last.reasoning.trim().isNotEmpty) {
        final thinking = process.rows.last as AssistantRow;
        expect(thinking.item.reasoning, last.reasoning);
        expect(thinking.item.text, isEmpty);
      }
    });

    test('off by default: the rows are what they always were', () {
      final s = runInfoState(finished: true);
      final rows = deriveRows(items: s.items, turns: s.turns);
      expect(rows.whereType<TurnProcessRow>(), isEmpty);
    });

    test('the running turn folds too: 思考中… · N 步', () {
      final s = runInfoState();
      final rows = deriveRows(items: s.items, turns: s.turns, running: true, foldTurns: true);
      final folds = rows.whereType<TurnProcessRow>().toList();
      expect(folds, hasLength(2), reason: '跑着的那一轮也有收起行');
      expect(folds.first.running, isFalse);
      expect(folds.last.running, isTrue);
      expect(turnProcessLabel(folds.last), startsWith('思考中… · '));
    });
  });

  // The cancelled fixture is one streamed reply with no process to fold: 一行
  // 思考/一段正文本来就只有一行,包一层没意义(纯问答不折叠这条保留)。
  test('a stopped turn with no process stays as one reply', () {
    final t = ChatTranscript();
    for (final line in File('../packages/dsh_client/test/fixtures/follow_cancel.jsonl').readAsLinesSync()) {
      if (line.trim().isEmpty) continue;
      t.apply(parseFollowFrame('s', jsonDecode(line)));
    }
    expect(t.turns.where((x) => !x.running), isNotEmpty);
    final rows = deriveRows(items: t.items, turns: t.turns, foldTurns: true);
    expect(rows.whereType<TurnProcessRow>(), isEmpty);
  });

  test('a stopped turn with a process folds, and says 已中断', () {
    final rows = deriveRows(
      items: [
        user(1, 'q'),
        assistant(2, '', reasoning: '先看一眼'),
        tool('a', 'bash', {'command': 'ls'}),
        assistant(4, '一半的回答'),
      ],
      turns: [trace(startSeq: 1, endSeq: 5, reason: 'aborted')],
      foldTurns: true,
    );
    final folds = rows.whereType<TurnProcessRow>().toList();
    expect(folds, hasLength(1), reason: '被停下的轮一样给收起行');
    expect(folds.single.running, isFalse);
    expect(turnProcessLabel(folds.single), startsWith('已中断 · '));
    expect((rows.last as AssistantRow).item.text, '一半的回答', reason: '回答留在折叠行外面');
  });

  group('synthetic', () {
    test('a plain answer with its thinking does not fold', () {
      final rows = deriveRows(
        items: [
          user(1, 'q'),
          assistant(2, 'a', reasoning: 'hmm'),
        ],
        turns: [trace(startSeq: 1, endSeq: 3)],
        foldTurns: true,
      );
      expect(types(rows), [UserRow, AssistantRow]);
      expect((rows[1] as AssistantRow).item.reasoning, 'hmm');
    });

    test('interim messages count; tools after the last reply fold it too', () {
      final rows = deriveRows(
        items: [
          user(1, 'q'),
          assistant(2, '先看看', reasoning: 'r1'),
          tool('a', 'bash', {'command': 'ls'}),
          assistant(4, '', reasoning: 'r2'),
          tool('b', 'read', {'file_path': 'x'}),
        ],
        turns: [trace(startSeq: 1, endSeq: 6)],
        foldTurns: true,
      );
      expect(types(rows), [UserRow, TurnProcessRow, TurnFootnoteRow]);
      final process = rows[1] as TurnProcessRow;
      expect(process.toolCalls, 2);
      expect(process.messages, 1);
      expect(turnProcessLabel(process), '已完成 · 3 步');
      // The interim reply carries no actions inside the fold.
      expect(process.rows.whereType<AssistantRow>().every((r) => !r.showActions), isTrue);
    });

    // Steered turns and several turns between two user messages fold piece
    // by piece: see rows_fold_test.dart.

    test('a failed turn folds too', () {
      final rows = deriveRows(
        items: [
          user(1, 'q'),
          tool('a', 'bash', {'command': 'ls'}),
          assistant(3, 'partial'),
        ],
        turns: [trace(startSeq: 1, endSeq: 4, reason: 'error')],
        foldTurns: true,
      );
      final folds = rows.whereType<TurnProcessRow>().toList();
      expect(folds, hasLength(1));
      expect(turnProcessLabel(folds.single), '出错 · 1 步');
    });
  });

  group('thinking preview', () {
    test('settled: the first line, without bold markers', () {
      expect(thinkingPreview('**看需求**\n用户想折叠工具调用'), '看需求');
    });

    test('the live window keeps the tail, without bold markers or blank runs', () {
      expect(thinkingTickerText('第一段\n**第二段**\n\n\n第三段\n正在写'), '第一段\n第二段\n第三段\n正在写');
      expect(thinkingTickerText('  先看看\n然后  '), '先看看\n然后');
    });
  });
}
