import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/uuid_v4.dart';
import 'package:movil/models/chat_message.dart';

void main() {
  group('ChatMessage.fromJson', () {
    test('mensaje completo', () {
      final m = ChatMessage.fromJson({
        'id': 'm1',
        'projectId': 'p1',
        'diagramId': 'd1',
        'seq': 7,
        'authorType': 'USER',
        'authorId': 'u1',
        'authorName': 'Ana',
        'authorColor': '#CC79A7',
        'addressedToId': 'u2',
        'addressedToName': 'Beto',
        'replyToId': 'm0',
        'clientMessageId': 'c1',
        'kind': 'CHAT',
        'text': 'hola',
        'createdAt': '2026-11-05T14:03:00Z',
        'meta': {'x': 1},
      });
      expect(m.id, 'm1');
      expect(m.seq, 7);
      expect(m.authorColor, '#CC79A7');
      expect(m.addressedToName, 'Beto');
      expect(m.createdAt, DateTime.utc(2026, 11, 5, 14, 3));
      expect(m.createdAt.isUtc, isTrue);
      expect(m.meta, {'x': 1});
      expect(m.esDeIa, isFalse);
    });

    test('opcionales OMITIDOS: mensaje de la IA sin authorColor ni authorId', () {
      final m = ChatMessage.fromJson({
        'id': 'm2',
        'projectId': 'p1',
        'seq': 8,
        'authorType': 'AI',
        'authorName': 'IA',
        'kind': 'ANSWER',
        'text': 'respuesta',
        'createdAt': '2026-11-05T14:03:05.123Z',
      });
      expect(m.esDeIa, isTrue);
      expect(m.diagramId, isNull);
      expect(m.authorId, isNull);
      expect(m.authorColor, isNull);
      expect(m.addressedToId, isNull);
      expect(m.addressedToName, isNull);
      expect(m.replyToId, isNull);
      expect(m.clientMessageId, isNull);
      expect(m.meta, isNull);
    });

    test('opcionales en null explicito o vacios', () {
      final m = ChatMessage.fromJson({
        'id': 'm3',
        'projectId': 'p1',
        'seq': 9,
        'authorType': 'USER',
        'authorName': 'Ana',
        'authorColor': null,
        'diagramId': null,
        'addressedToName': '',
        'meta': null,
        'kind': 'CHAT',
        'text': 'x',
        'createdAt': '2026-11-05T14:00:00Z',
      });
      expect(m.authorColor, isNull);
      expect(m.diagramId, isNull);
      expect(m.addressedToName, isNull);
      expect(m.meta, isNull);
    });

    test('mapa vacio o tipos raros: no tira, usa valores por defecto', () {
      final m = ChatMessage.fromJson({'seq': '12', 'createdAt': 'no-es-fecha', 'meta': 'texto', 'text': 5});
      expect(m.id, '');
      expect(m.seq, 12);
      expect(m.text, '');
      expect(m.authorType, 'USER');
      expect(m.kind, 'CHAT');
      expect(m.meta, isNull);
      expect(m.createdAt, DateTime.fromMillisecondsSinceEpoch(0, isUtc: true));

      final vacio = ChatMessage.fromJson({});
      expect(vacio.seq, 0);
    });

    test('SYSTEM se reconoce por authorType o por kind', () {
      expect(ChatMessage.fromJson({'authorType': 'SYSTEM'}).esDeSistema, isTrue);
      expect(ChatMessage.fromJson({'authorType': 'AI', 'kind': 'SYSTEM'}).esDeSistema, isTrue);
      expect(ChatMessage.fromJson({'authorType': 'USER', 'kind': 'CHAT'}).esDeSistema, isFalse);
    });
  });

  group('ChatPage.fromJson', () {
    test('lee messages y hasMore, ignora entradas que no son objetos', () {
      final p = ChatPage.fromJson({
        'messages': [
          {'id': 'a', 'seq': 1},
          'basura',
          null,
          {'id': 'b', 'seq': 2},
        ],
        'hasMore': true,
      });
      expect(p.messages.map((m) => m.id), ['a', 'b']);
      expect(p.hasMore, isTrue);
    });

    test('sin messages ni hasMore', () {
      final p = ChatPage.fromJson({});
      expect(p.messages, isEmpty);
      expect(p.hasMore, isFalse);
    });
  });

  test('uuidV4 tiene formato v4 y no usa prefijos reservados', () {
    final formato = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');
    final vistos = <String>{};
    for (var i = 0; i < 200; i++) {
      final u = uuidV4();
      expect(formato.hasMatch(u), isTrue, reason: u);
      expect(u.startsWith('ai-') || u.startsWith('sys-'), isFalse);
      vistos.add(u);
    }
    expect(vistos.length, 200);
  });
}
