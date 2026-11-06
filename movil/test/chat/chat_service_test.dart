import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:movil/services/chat_service.dart';

const _mensaje = {
  'id': 'm1',
  'projectId': 'p1',
  'seq': 3,
  'authorType': 'USER',
  'authorId': 'u1',
  'authorName': 'Ana',
  'clientMessageId': 'c1',
  'kind': 'CHAT',
  'text': 'hola @IA',
  'createdAt': '2026-11-05T14:00:00Z',
};

ChatService _servicio(MockClientHandler handler) =>
    ChatService(direccionServidor: 'http://10.0.2.2:8080', token: 'tok', client: MockClient(handler));

http.Response _json(Object cuerpo, int status) =>
    http.Response(jsonEncode(cuerpo), status, headers: {'content-type': 'application/json'});

void main() {
  group('lecturas', () {
    test('fetchLatest: GET con limit y token', () async {
      late http.Request pedido;
      final s = _servicio((r) async {
        pedido = r;
        return _json({'messages': [_mensaje], 'hasMore': false}, 200);
      });
      final p = await s.fetchLatest('p1');
      expect(pedido.method, 'GET');
      expect(pedido.url.path, '/api/v1/projects/p1/chat');
      expect(pedido.url.queryParameters, {'limit': '50'});
      expect(pedido.headers['Authorization'], 'Bearer tok');
      expect(p.messages.single.id, 'm1');
    });

    test('fetchAfter y fetchBefore mandan su cursor; limit se acota a 100', () async {
      final urls = <Uri>[];
      final s = _servicio((r) async {
        urls.add(r.url);
        return _json({'messages': [], 'hasMore': true}, 200);
      });
      final p = await s.fetchAfter('p1', 10, limit: 500);
      await s.fetchBefore('p1', 4);
      expect(p.hasMore, isTrue);
      expect(urls[0].queryParameters, {'afterSeq': '10', 'limit': '100'});
      expect(urls[1].queryParameters, {'beforeSeq': '4', 'limit': '50'});
    });
  });

  group('estado de lectura (CU23)', () {
    test('fetchReadState: GET /chat/read con token', () async {
      late http.Request pedido;
      final s = _servicio((r) async {
        pedido = r;
        return _json({'lastReadSeq': 10, 'unreadCount': 3, 'lastSeq': 14}, 200);
      });
      final e = await s.fetchReadState('p1');
      expect(pedido.method, 'GET');
      expect(pedido.url.path, '/api/v1/projects/p1/chat/read');
      expect(pedido.headers['Authorization'], 'Bearer tok');
      expect([e.lastReadSeq, e.unreadCount, e.lastSeq], [10, 3, 14]);
    });

    test('markRead: PUT /chat/read con {upToSeq} y devuelve el estado', () async {
      late http.Request pedido;
      final s = _servicio((r) async {
        pedido = r;
        return _json({'lastReadSeq': 14, 'unreadCount': 0, 'lastSeq': 14}, 200);
      });
      final e = await s.markRead('p1', 14);
      expect(pedido.method, 'PUT');
      expect(pedido.url.path, '/api/v1/projects/p1/chat/read');
      expect(jsonDecode(pedido.body), {'upToSeq': 14});
      expect(e.unreadCount, 0);
    });

    test('errores traducidos: 403 y sin red', () async {
      final s403 = _servicio((_) async => http.Response('', 403));
      await expectLater(
        s403.fetchReadState('p1'),
        throwsA(isA<ChatException>().having((e) => e.mensaje, 'mensaje', 'No eres miembro de este proyecto.')),
      );
      final sinRed = _servicio((_) async => throw http.ClientException('sin red'));
      await expectLater(
        sinRed.markRead('p1', 3),
        throwsA(isA<ChatException>().having((e) => e.sinConexion, 'sinConexion', isTrue)),
      );
    });
  });

  group('send', () {
    test('cuerpo sin autor ni opcionales nulos; 201 devuelve el mensaje', () async {
      late Map<String, dynamic> cuerpo;
      final s = _servicio((r) async {
        cuerpo = jsonDecode(r.body) as Map<String, dynamic>;
        expect(r.method, 'POST');
        expect(r.headers['Content-Type'], startsWith('application/json'));
        return _json(_mensaje, 201);
      });
      final m = await s.send('p1', 'hola @IA', 'c1');
      expect(cuerpo, {'clientMessageId': 'c1', 'text': 'hola @IA'});
      expect(m.seq, 3);
    });

    test('incluye diagramId y replyToId cuando vienen', () async {
      late Map<String, dynamic> cuerpo;
      final s = _servicio((r) async {
        cuerpo = jsonDecode(r.body) as Map<String, dynamic>;
        return _json(_mensaje, 201);
      });
      await s.send('p1', 'x', 'c1', diagramId: 'd1', replyToId: 'm0');
      expect(cuerpo, {'clientMessageId': 'c1', 'text': 'x', 'diagramId': 'd1', 'replyToId': 'm0'});
    });

    test('200 (duplicado) tambien es exito', () async {
      final s = _servicio((r) async => _json(_mensaje, 200));
      expect((await s.send('p1', 'x', 'c1')).id, 'm1');
    });

    test('texto vacio o de mas de 2000 caracteres no llega al servidor', () async {
      var llamadas = 0;
      final s = _servicio((r) async {
        llamadas++;
        return _json(_mensaje, 201);
      });
      await expectLater(s.send('p1', '   ', 'c1'), throwsA(isA<ChatException>()));
      await expectLater(s.send('p1', 'a' * 2001, 'c1'), throwsA(isA<ChatException>()));
      await s.send('p1', 'a' * 2000, 'c1');
      expect(llamadas, 1);
    });
  });

  group('errores traducidos', () {
    Future<ChatException> fallo(http.Response r) async {
      final s = _servicio((_) async => r);
      try {
        await s.send('p1', 'x', 'c1');
      } on ChatException catch (e) {
        return e;
      }
      fail('debia fallar');
    }

    test('403 sin code', () async {
      final e = await fallo(http.Response('', 403));
      expect(e.noEsMiembro, isTrue);
      expect(e.mensaje, 'No eres miembro de este proyecto.');
    });

    test('429 RATE_LIMITED', () async {
      final e = await fallo(_json({'code': 'RATE_LIMITED', 'message': 'rate'}, 429));
      expect(e.code, 'RATE_LIMITED');
      expect(e.mensaje, 'Estás enviando demasiados mensajes; espera un momento.');
    });

    test('400 INVALID_PAYLOAD usa el message del servidor', () async {
      final e = await fallo(_json({'code': 'INVALID_PAYLOAD', 'message': 'El texto es muy largo'}, 400));
      expect(e.code, 'INVALID_PAYLOAD');
      expect(e.mensaje, 'El texto es muy largo');
    });

    test('red caida -> status 0 (sinConexion)', () async {
      final s = _servicio((_) async => throw http.ClientException('sin red'));
      await expectLater(
        s.fetchAfter('p1', 1),
        throwsA(isA<ChatException>().having((e) => e.sinConexion, 'sinConexion', isTrue)),
      );
    });

    test('500 generico y cuerpo no JSON en 200', () async {
      expect((await fallo(http.Response('boom', 500))).mensaje, 'El servidor respondió 500.');
      final s = _servicio((_) async => http.Response('<html>', 200));
      await expectLater(
        s.fetchLatest('p1'),
        throwsA(isA<ChatException>().having((e) => e.status, 'status', -1)),
      );
    });
  });
}
