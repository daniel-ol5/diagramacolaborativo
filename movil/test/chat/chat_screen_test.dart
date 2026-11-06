import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:movil/services/chat_service.dart';
import 'package:movil/services/config_service.dart';
import 'package:movil/ui/chat_screen.dart';
import 'package:movil/voz/servicio_de_voz.dart';

const _sesion = Sesion(
  token: 't',
  userId: 'u1',
  email: 'ana@x.com',
  nombreCompleto: 'Ana',
  direccionServidor: 'http://servidor',
);

Map<String, dynamic> _m(String id, int seq, String text,
        {String type = 'USER', String? author, String name = 'Beto', String? to, String? toId, String? cmid, String kind = 'CHAT'}) =>
    {
      'id': id,
      'projectId': 'p1',
      'seq': seq,
      'authorType': type,
      'authorId': ?author,
      'authorName': name,
      'addressedToName': ?to,
      'addressedToId': ?toId,
      'clientMessageId': ?cmid,
      'kind': kind,
      'text': text,
      'createdAt': '2026-11-05T14:00:00Z',
    };

http.Response _json(Object o, int status) =>
    http.Response(jsonEncode(o), status, headers: {'content-type': 'application/json'});

/// Mismo contrato que ServicioDeVoz, sin tocar el microfono real.
class VozFalsa implements ServicioDeVoz {
  VozFalsa({this.ok = true, this.texto = ''});

  final bool ok;
  final String texto;
  int iniciados = 0;
  int escuchados = 0;

  @override
  bool get listo => ok;
  @override
  String? get idioma => 'es-US';
  @override
  Future<bool> iniciar() async {
    iniciados++;
    return ok;
  }

  @override
  Future<String> escuchar({Duration duracion = const Duration(seconds: 8)}) async {
    escuchados++;
    await Future<void>.delayed(const Duration(seconds: 2));
    return texto;
  }

  @override
  Future<void> detener() async {}
  @override
  Future<void> decir(String texto) async {}
}

Future<void> _abrir(WidgetTester tester, MockClientHandler handler, {ServicioDeVoz? voz}) async {
  final servicio = ChatService(direccionServidor: 'http://servidor', token: 't', client: MockClient(handler));
  await tester.pumpWidget(MaterialApp(
    home: ChatScreen(sesion: _sesion, projectId: 'p1', projectName: 'Tienda', servicio: servicio, voz: voz),
  ));
  await tester.pump();
  await tester.pump();
}

void main() {
  testWidgets('muestra burbujas: mias a la derecha, IA con etiqueta, SYSTEM', (tester) async {
    await _abrir(tester, (r) async => _json({
          'messages': [
            _m('a', 1, 'hola equipo', author: 'u2', name: 'Beto'),
            _m('b', 2, 'mi mensaje @IA', author: 'u1', name: 'Ana', cmid: 'c0'),
            _m('c', 4, 'respuesta de la IA', type: 'AI', name: 'IA', to: 'Ana', kind: 'ANSWER'),
            _m('d', 5, 'otra de la IA', type: 'AI', name: 'IA', kind: 'ANSWER'),
            _m('e', 6, 'La IA no está disponible ahora', type: 'SYSTEM', name: 'Sistema', kind: 'SYSTEM'),
          ],
          'hasMore': false,
        }, 200));

    expect(find.text('Tienda'), findsOneWidget);
    expect(find.text('IA · para Ana'), findsOneWidget);
    expect(find.text('IA'), findsOneWidget);
    expect(find.textContaining('La IA no está disponible ahora'), findsOneWidget);
    final mio = tester.getCenter(find.text('mi mensaje @IA')).dx;
    final ajeno = tester.getCenter(find.text('hola equipo')).dx;
    expect(mio, greaterThan(ajeno));
  });

  testWidgets('envio optimista: "Enviando…" y luego confirmado', (tester) async {
    final respuesta = Completer<http.Response>();
    Map<String, dynamic>? cuerpo;
    await _abrir(tester, (r) async {
      if (r.method == 'GET') return _json({'messages': [], 'hasMore': false}, 200);
      cuerpo = jsonDecode(r.body) as Map<String, dynamic>;
      return respuesta.future;
    });

    await tester.enterText(find.byType(TextField), 'hola');
    await tester.pump();
    await tester.tap(find.byTooltip('Enviar'));
    await tester.pump();
    expect(find.text('hola'), findsOneWidget);
    expect(find.text('Enviando…'), findsOneWidget);
    expect(cuerpo!.keys.toSet(), {'clientMessageId', 'text'});

    respuesta.complete(_json(_m('m1', 1, 'hola', author: 'u1', name: 'Ana', cmid: cuerpo!['clientMessageId'] as String), 201));
    await tester.pump();
    await tester.pump();
    expect(find.text('Enviando…'), findsNothing);
    expect(find.text('hola'), findsOneWidget);
  });

  testWidgets('falla con 429 y Reintentar usa el MISMO clientMessageId', (tester) async {
    final cmids = <String>[];
    await _abrir(tester, (r) async {
      if (r.method == 'GET') return _json({'messages': [], 'hasMore': false}, 200);
      final cmid = (jsonDecode(r.body) as Map<String, dynamic>)['clientMessageId'] as String;
      cmids.add(cmid);
      if (cmids.length == 1) return _json({'code': 'RATE_LIMITED', 'message': 'x'}, 429);
      return _json(_m('m1', 1, 'hola', author: 'u1', name: 'Ana', cmid: cmid), 201);
    });

    await tester.enterText(find.byType(TextField), 'hola');
    await tester.pump();
    await tester.tap(find.byTooltip('Enviar'));
    await tester.pump();
    await tester.pump();
    expect(find.text('No se envió'), findsOneWidget);
    expect(find.text('Estás enviando demasiados mensajes; espera un momento.'), findsOneWidget);

    await tester.tap(find.text('Reintentar'));
    await tester.pump();
    await tester.pump();
    expect(find.text('No se envió'), findsNothing);
    expect(cmids.length, 2);
    expect(cmids[0], cmids[1]);
  });

  testWidgets('sin conexion al enviar: aviso y campo deshabilitado', (tester) async {
    await _abrir(tester, (r) async {
      if (r.method == 'GET') return _json({'messages': [], 'hasMore': false}, 200);
      throw http.ClientException('sin red');
    });
    await tester.enterText(find.byType(TextField), 'hola');
    await tester.pump();
    await tester.tap(find.byTooltip('Enviar'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Sin conexión con el servidor.'), findsOneWidget);
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);
  });

  testWidgets('403 en la carga: "No eres miembro de este proyecto."', (tester) async {
    await _abrir(tester, (r) async => http.Response('', 403));
    expect(find.text('No eres miembro de este proyecto.'), findsOneWidget);
  });

  testWidgets('"Preguntar a la IA" inserta "@IA " separado de la palabra anterior', (tester) async {
    await _abrir(tester, (r) async => _json({'messages': [], 'hasMore': false}, 200));
    await tester.tap(find.text('Preguntar a la IA'));
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, '@IA ');

    await tester.enterText(find.byType(TextField), 'hola');
    await tester.pump();
    await tester.tap(find.text('Preguntar a la IA'));
    await tester.pump();
    expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, 'hola @IA ');
  });

  testWidgets('el contador aparece solo cerca del limite de 2000', (tester) async {
    await _abrir(tester, (r) async => _json({'messages': [], 'hasMore': false}, 200));
    await tester.enterText(find.byType(TextField), 'a' * 100);
    await tester.pump();
    expect(find.textContaining('/ 2000'), findsNothing);
    await tester.enterText(find.byType(TextField), 'a' * 1900);
    await tester.pump();
    expect(find.text('1900 / 2000'), findsOneWidget);
  });

  group('sondeo', () {
    testWidgets('cada 3 s trae lo nuevo con afterSeq; pausa en segundo plano y reanuda al volver', (tester) async {
      final gets = <Map<String, String>>[];
      await _abrir(tester, (r) async {
        // GET/PUT /chat/read (mensajes nuevos) no es parte del sondeo.
        if (r.url.path.endsWith('/read')) return _json({'lastReadSeq': 0, 'unreadCount': 0, 'lastSeq': 0}, 200);
        gets.add(r.url.queryParameters);
        final after = r.url.queryParameters['afterSeq'];
        if (after == null) return _json({'messages': [_m('a', 1, 'primero')], 'hasMore': false}, 200);
        if (after == '1') return _json({'messages': [_m('b', 3, 'llegó por sondeo')], 'hasMore': false}, 200);
        return _json({'messages': [], 'hasMore': false}, 200);
      });
      expect(gets.length, 1);

      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(gets.last, {'afterSeq': '1', 'limit': '50'});
      expect(find.text('llegó por sondeo'), findsOneWidget);

      // Segundo plano: no se pide nada mas.
      for (final s in [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]) {
        tester.binding.handleAppLifecycleStateChanged(s);
      }
      final antes = gets.length;
      await tester.pump(const Duration(seconds: 15));
      expect(gets.length, antes);

      // Al volver: un pedido en el acto (sin esperar 3 s), desde el ultimo seq.
      for (final s in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
        tester.binding.handleAppLifecycleStateChanged(s);
      }
      await tester.pump();
      expect(gets.length, antes + 1);
      expect(gets.last['afterSeq'], '3');
    });

    testWidgets('hasMore=true vuelve a pedir en el acto, sin duplicar', (tester) async {
      final cursores = <String?>[];
      await _abrir(tester, (r) async {
        if (r.url.path.endsWith('/read')) return _json({'lastReadSeq': 0, 'unreadCount': 0, 'lastSeq': 0}, 200);
        final after = r.url.queryParameters['afterSeq'];
        cursores.add(after);
        switch (after) {
          case null:
            return _json({'messages': [_m('a', 1, 'uno')], 'hasMore': false}, 200);
          case '1':
            return _json({'messages': [_m('a', 1, 'uno'), _m('b', 2, 'dos')], 'hasMore': true}, 200);
          case '2':
            return _json({'messages': [_m('c', 4, 'tres')], 'hasMore': false}, 200);
          default:
            return _json({'messages': [], 'hasMore': false}, 200);
        }
      });
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      await tester.pump();
      expect(cursores, [null, '1', '2']);
      expect(find.text('uno'), findsOneWidget);
      expect(find.text('dos'), findsOneWidget);
      expect(find.text('tres'), findsOneWidget);
    });

    testWidgets('sin red en el sondeo: aviso y campo deshabilitado; al volver la red, se habilita', (tester) async {
      var hayRed = true;
      await _abrir(tester, (r) async {
        if (!hayRed) throw http.ClientException('sin red');
        return _json({'messages': [], 'hasMore': false}, 200);
      });
      hayRed = false;
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('Sin conexión con el servidor.'), findsOneWidget);
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isFalse);

      hayRed = true;
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('Sin conexión con el servidor.'), findsNothing);
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isTrue);
    });
  });

  group('La IA está respondiendo…', () {
    Future<void> enviarConIa(WidgetTester tester, {required bool iaContesta}) async {
      var enviado = false;
      await _abrir(tester, (r) async {
        if (r.method == 'POST') {
          enviado = true;
          final cmid = (jsonDecode(r.body) as Map<String, dynamic>)['clientMessageId'] as String;
          return _json(_m('m1', 1, '@IA ¿qué falta?', author: 'u1', name: 'Ana', cmid: cmid), 201);
        }
        if (enviado && iaContesta && r.url.queryParameters['afterSeq'] == '1') {
          return _json({
            'messages': [_m('r1', 2, 'Falta la clase Pago.', type: 'AI', name: 'IA', to: 'Ana', toId: 'u1')],
            'hasMore': false,
          }, 200);
        }
        return _json({'messages': [], 'hasMore': false}, 200);
      });
      await tester.enterText(find.byType(TextField), '@IA ¿qué falta?');
      await tester.pump();
      await tester.tap(find.byTooltip('Enviar'));
      await tester.pump();
      await tester.pump();
    }

    testWidgets('aparece al enviar con @IA y se va cuando llega la respuesta para mi', (tester) async {
      await enviarConIa(tester, iaContesta: true);
      expect(find.text('La IA está respondiendo…'), findsOneWidget);
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('Falta la clase Pago.'), findsOneWidget);
      expect(find.text('La IA está respondiendo…'), findsNothing);
    });

    testWidgets('si la IA no contesta, se va a los 30 s', (tester) async {
      await enviarConIa(tester, iaContesta: false);
      expect(find.text('La IA está respondiendo…'), findsOneWidget);
      await tester.pump(const Duration(seconds: 29));
      expect(find.text('La IA está respondiendo…'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      expect(find.text('La IA está respondiendo…'), findsNothing);
    });

    testWidgets('un mensaje sin @IA no lo muestra', (tester) async {
      await _abrir(tester, (r) async {
        if (r.method == 'POST') return _json(_m('m1', 1, 'hola', author: 'u1', name: 'Ana', cmid: 'x'), 201);
        return _json({'messages': [], 'hasMore': false}, 200);
      });
      await tester.enterText(find.byType(TextField), 'hola');
      await tester.pump();
      await tester.tap(find.byTooltip('Enviar'));
      await tester.pump();
      await tester.pump();
      expect(find.text('La IA está respondiendo…'), findsNothing);
    });
  });

  group('micrófono', () {
    String campo(WidgetTester tester) => tester.widget<TextField>(find.byType(TextField)).controller!.text;

    testWidgets('dicta dentro del campo y NO envia', (tester) async {
      var posts = 0;
      final voz = VozFalsa(texto: 'agrega una clase Pago');
      await _abrir(tester, (r) async {
        if (r.method == 'POST') posts++;
        return _json({'messages': [], 'hasMore': false}, 200);
      }, voz: voz);
      await tester.enterText(find.byType(TextField), 'hola');
      await tester.pump();

      await tester.tap(find.byTooltip('Dictar mensaje'));
      await tester.pump();
      await tester.pump();
      expect(find.text('Escuchando… habla ahora (hasta 8 segundos).'), findsOneWidget);

      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.textContaining('Escuchando…'), findsNothing);
      expect(campo(tester), 'hola agrega una clase Pago');
      expect(posts, 0);
    });

    testWidgets('sin permiso: aviso, el campo no cambia y se reintenta al volver a tocar', (tester) async {
      final voz = VozFalsa(ok: false);
      await _abrir(tester, (r) async => _json({'messages': [], 'hasMore': false}, 200), voz: voz);
      await tester.tap(find.byTooltip('Dictar mensaje'));
      await tester.pump();
      await tester.pump();
      expect(find.textContaining('permiso de micrófono'), findsOneWidget);
      expect(campo(tester), '');
      expect(voz.escuchados, 0);

      await tester.tap(find.byTooltip('Dictar mensaje'));
      await tester.pump();
      expect(voz.iniciados, 2);
    });

    testWidgets('si no se entendio nada, lo avisa', (tester) async {
      await _abrir(tester, (r) async => _json({'messages': [], 'hasMore': false}, 200), voz: VozFalsa(texto: '  '));
      await tester.tap(find.byTooltip('Dictar mensaje'));
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pump();
      expect(find.text('No se entendió nada. Intenta de nuevo.'), findsOneWidget);
      expect(campo(tester), '');
    });
  });

  testWidgets('accesibilidad: areas tactiles de 48 dp, etiquetas y contraste', (tester) async {
    final handle = tester.ensureSemantics();
    await _abrir(tester, (r) async {
      if (r.method == 'POST') return _json({'code': 'RATE_LIMITED', 'message': 'x'}, 429);
      return _json({
        'messages': [
          _m('a', 1, 'hola equipo', author: 'u2', name: 'Beto'),
          _m('b', 2, 'mi mensaje', author: 'u1', name: 'Ana', cmid: 'c0'),
          _m('c', 3, 'respuesta', type: 'AI', name: 'IA', to: 'Ana', toId: 'u1', kind: 'ANSWER'),
          _m('d', 4, 'La IA no está disponible ahora', type: 'SYSTEM', name: 'Sistema', kind: 'SYSTEM'),
        ],
        'hasMore': false,
      }, 200);
    });
    // Un mensaje fallido, para que aparezca el boton Reintentar.
    await tester.enterText(find.byType(TextField), 'hola');
    await tester.pump();
    await tester.tap(find.byTooltip('Enviar'));
    await tester.pump();
    await tester.pump();
    ScaffoldMessenger.of(tester.element(find.byType(ChatScreen))).removeCurrentSnackBar();
    await tester.pump();
    expect(find.text('Reintentar'), findsOneWidget);

    await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    await expectLater(tester, meetsGuideline(textContrastGuideline));
    handle.dispose();
  });

  group('teclado', () {
    Future<void> abrirConMensajes(WidgetTester tester) async {
      await _abrir(
        tester,
        (r) async => _json({
          'messages': [for (var i = 1; i <= 30; i++) _m('m$i', i, 'mensaje $i')],
          'hasMore': false,
        }, 200),
      );
      // El salto al final se completa en los frames siguientes.
      for (var i = 0; i < 5; i++) {
        await tester.pump();
      }
    }

    bool campoConFoco(WidgetTester tester) =>
        tester.widget<EditableText>(find.byType(EditableText)).focusNode.hasFocus;

    testWidgets('tocar la conversacion baja el teclado', (tester) async {
      await abrirConMensajes(tester);
      await tester.showKeyboard(find.byType(TextField));
      expect(campoConFoco(tester), isTrue);
      await tester.tap(find.text('mensaje 30'));
      await tester.pump();
      expect(campoConFoco(tester), isFalse);
    });

    testWidgets('deslizar la conversacion baja el teclado', (tester) async {
      await abrirConMensajes(tester);
      await tester.showKeyboard(find.byType(TextField));
      expect(campoConFoco(tester), isTrue);
      await tester.drag(find.byType(CustomScrollView), const Offset(0, 300));
      await tester.pump();
      expect(campoConFoco(tester), isFalse);
    });

    testWidgets('escribir y "Preguntar a la IA" no bajan el teclado', (tester) async {
      await abrirConMensajes(tester);
      await tester.showKeyboard(find.byType(TextField));
      await tester.enterText(find.byType(TextField), 'hola');
      await tester.tap(find.text('Preguntar a la IA'));
      await tester.pump();
      expect(campoConFoco(tester), isTrue);
    });
  });
}
