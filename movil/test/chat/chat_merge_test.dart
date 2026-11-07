import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/chat_merge.dart';
import 'package:movil/models/chat_message.dart';

ChatMessage msg(String id, int seq, {String? cmid, String text = ''}) => ChatMessage.fromJson({
      'id': id,
      'seq': seq,
      'clientMessageId': ?cmid,
      'text': text,
      'createdAt': '2026-11-05T14:00:00Z',
    });

List<String> ids(Iterable<ChatMessage> l) => [for (final m in l) m.id];

void main() {
  group('fusionarMensajes', () {
    test('ordena por seq aunque lleguen desordenados y con huecos', () {
      final r = fusionarMensajes([msg('c', 9), msg('a', 1)], [msg('b', 4)]);
      expect(ids(r), ['a', 'b', 'c']);
    });

    test('deduplica por id: gana la version nueva', () {
      final r = fusionarMensajes([msg('a', 1, text: 'viejo')], [msg('a', 1, text: 'nuevo'), msg('b', 2)]);
      expect(ids(r), ['a', 'b']);
      expect(r.first.text, 'nuevo');
    });

    test('deduplica por clientMessageId: queda el de menor seq', () {
      final r = fusionarMensajes([msg('a', 3, cmid: 'c1')], [msg('b', 5, cmid: 'c1'), msg('x', 4)]);
      expect(ids(r), ['a', 'x']);
    });

    test('mensajes sin clientMessageId (IA, sistema) no chocan entre si', () {
      final r = fusionarMensajes([msg('a', 1)], [msg('b', 2)]);
      expect(ids(r), ['a', 'b']);
    });

    test('descarta mensajes sin id', () {
      expect(ids(fusionarMensajes([], [msg('', 1), msg('a', 2)])), ['a']);
    });
  });

  group('ChatTimeline', () {
    final t0 = DateTime.utc(2026, 11, 5);

    test('fusionar devuelve solo los nuevos y actualiza ultimoSeq', () {
      final t = ChatTimeline();
      expect(t.ultimoSeq, isNull);
      expect(ids(t.fusionar([msg('a', 1), msg('b', 2)])), ['a', 'b']);
      expect(ids(t.fusionar([msg('b', 2), msg('c', 7)])), ['c']);
      expect(t.ultimoSeq, 7);
      expect(t.total, 3);
    });

    test('pendiente -> confirmado por la respuesta del POST', () {
      final t = ChatTimeline()..agregarPendiente('c1', 'hola', t0);
      expect(t.pendientes.single.fallido, isFalse);
      t.fusionar([msg('m1', 3, cmid: 'c1')]);
      expect(t.pendientes, isEmpty);
      expect(ids(t.mensajes), ['m1']);
    });

    test('el sondeo trae el mensaje antes que el POST: no se duplica', () {
      final t = ChatTimeline()..agregarPendiente('c1', 'hola', t0);
      t.fusionar([msg('m1', 3, cmid: 'c1')]); // sondeo
      t.fusionar([msg('m1', 3, cmid: 'c1')]); // respuesta del POST
      expect(t.pendientes, isEmpty);
      expect(ids(t.mensajes), ['m1']);
    });

    test('fallido -> reintentar conserva el mismo clientMessageId', () {
      final t = ChatTimeline()..agregarPendiente('c1', 'hola', t0);
      t.marcarFallido('c1', 'Sin conexión');
      expect(t.pendiente('c1')!.fallido, isTrue);
      expect(t.pendiente('c1')!.error, 'Sin conexión');
      t.marcarEnviando('c1');
      final p = t.pendiente('c1')!;
      expect(p.fallido, isFalse);
      expect(p.clientMessageId, 'c1');
      expect(p.text, 'hola');
    });

    test('no agrega dos veces el mismo pendiente ni uno ya confirmado', () {
      final t = ChatTimeline()
        ..agregarPendiente('c1', 'hola', t0)
        ..agregarPendiente('c1', 'hola', t0);
      expect(t.pendientes.length, 1);
      t.fusionar([msg('m1', 1, cmid: 'c1')]);
      t.agregarPendiente('c1', 'hola', t0);
      expect(t.pendientes, isEmpty);
    });
  });

  group('anteponer historial (CU23)', () {
    final t0 = DateTime.utc(2026, 11, 5);

    test('primerSeq es el menor seq cargado', () {
      final t = ChatTimeline();
      expect(t.primerSeq, isNull);
      t.fusionar([msg('b', 8), msg('a', 5)]);
      expect(t.primerSeq, 5);
      expect(t.ultimoSeq, 8);
    });

    test('pagina vieja con huecos y solapada: ordenada y sin duplicados', () {
      final t = ChatTimeline()..fusionar([msg('m50', 50), msg('m51', 51), msg('m53', 53)]);
      final nuevos = t.fusionar([msg('m40', 40), msg('m47', 47), msg('m50', 50)]);
      expect(ids(t.mensajes), ['m40', 'm47', 'm50', 'm51', 'm53']);
      expect(ids(nuevos), ['m40', 'm47']);
      expect(t.primerSeq, 40);
      expect(t.ultimoSeq, 53, reason: 'anteponer no mueve el cursor del sondeo');
    });

    test('anteponer y sondear en cualquier orden da lo mismo', () {
      final a = ChatTimeline()..fusionar([msg('m10', 10)]);
      a
        ..fusionar([msg('m5', 5)])
        ..fusionar([msg('m12', 12)]);
      final b = ChatTimeline()..fusionar([msg('m10', 10)]);
      b
        ..fusionar([msg('m12', 12)])
        ..fusionar([msg('m5', 5)]);
      expect(ids(a.mensajes), ids(b.mensajes));
    });

    test('los pendientes propios siguen al final y no se tocan', () {
      final t = ChatTimeline()
        ..fusionar([msg('m50', 50)])
        ..agregarPendiente('c1', 'hola', t0);
      t.fusionar([msg('m40', 40, cmid: 'otro')]);
      expect(t.pendientes.single.clientMessageId, 'c1');
      expect(ids(t.mensajes), ['m40', 'm50']);
    });
  });
}
