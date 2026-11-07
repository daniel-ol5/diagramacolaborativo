import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:movil/services/chat_service.dart';
import 'package:movil/services/config_service.dart';
import 'package:movil/ui/chat_screen.dart';

// CU23 en la pantalla: linea "Mensajes nuevos", boton flotante y marcar
// como leido (PUT /chat/read).

const _sesion = Sesion(
  token: 't',
  userId: 'u1',
  email: 'ana@x.com',
  nombreCompleto: 'Ana',
  direccionServidor: 'http://servidor',
);

Map<String, dynamic> _m(int seq, {String autor = 'u2'}) => {
      'id': 'm$seq',
      'projectId': 'p1',
      'seq': seq,
      'authorType': 'USER',
      'authorId': autor,
      'authorName': autor == 'u1' ? 'Ana' : 'Beto',
      'kind': 'CHAT',
      'text': 'mensaje $seq',
      'createdAt': DateTime.now().toUtc().toIso8601String(),
    };

http.Response _json(Object o) => http.Response(jsonEncode(o), 200, headers: {'content-type': 'application/json'});

http.Response _pagina(List<Map<String, dynamic>> m, {bool hasMore = false}) =>
    _json({'messages': m, 'hasMore': hasMore});

/// Servidor falso: pagina inicial, sondeo, estado de lectura y PUTs.
class Servidor {
  Servidor({required this.inicial, this.lastRead = 0});

  List<Map<String, dynamic>> inicial;
  int lastRead;
  bool inicialHasMore = false;
  final nuevos = <Map<String, dynamic>>[];
  final puts = <int>[];
  Completer<http.Response>? lecturaLenta;
  bool lecturaFalla = false;

  Future<http.Response> responder(http.Request r) async {
    if (r.url.path.endsWith('/read')) {
      if (r.method == 'PUT') {
        final seq = (jsonDecode(r.body) as Map<String, dynamic>)['upToSeq'] as int;
        puts.add(seq);
        if (seq > lastRead) lastRead = seq;
        return _json({'lastReadSeq': lastRead, 'unreadCount': 0, 'lastSeq': lastRead});
      }
      if (lecturaFalla) return http.Response('', 500);
      if (lecturaLenta != null) return lecturaLenta!.future;
      return _json({'lastReadSeq': lastRead, 'unreadCount': 0, 'lastSeq': 0});
    }
    final q = r.url.queryParameters;
    if (q['afterSeq'] != null) {
      final after = int.parse(q['afterSeq']!);
      return _pagina([for (final m in nuevos) if ((m['seq'] as int) > after) m]);
    }
    if (q['beforeSeq'] != null) return _pagina([]);
    return _pagina(inicial, hasMore: inicialHasMore);
  }
}

Future<void> _abrir(WidgetTester tester, Servidor servidor) async {
  final servicio = ChatService(
    direccionServidor: 'http://servidor',
    token: 't',
    client: MockClient(servidor.responder),
  );
  await tester.pumpWidget(MaterialApp(
    home: ChatScreen(sesion: _sesion, projectId: 'p1', projectName: 'Tienda', servicio: servicio),
  ));
  for (var i = 0; i < 6; i++) {
    await tester.pump();
  }
}

final _lista = find.byType(CustomScrollView);

List<Map<String, dynamic>> _rango(int desde, int hasta, {String autor = 'u2'}) =>
    [for (var i = desde; i <= hasta; i++) _m(i, autor: autor)];

void main() {
  group('linea "Mensajes nuevos"', () {
    testWidgets('va encima del primer mensaje de otro que no habia leido', (tester) async {
      await _abrir(tester, Servidor(inicial: [..._rango(1, 4), _m(5, autor: 'u1'), ..._rango(6, 8)], lastRead: 4));
      expect(find.text('Mensajes nuevos'), findsOneWidget);
      final linea = tester.getTopLeft(find.text('Mensajes nuevos')).dy;
      expect(linea, greaterThan(tester.getTopLeft(find.text('mensaje 5')).dy), reason: 'el 5 es mio: no cuenta');
      expect(linea, lessThan(tester.getTopLeft(find.text('mensaje 6')).dy));
      expect(find.bySemanticsLabel('Mensajes nuevos'), findsOneWidget);
    });

    testWidgets('sin no leidos no hay linea', (tester) async {
      await _abrir(tester, Servidor(inicial: _rango(1, 8), lastRead: 8));
      expect(find.text('Mensajes nuevos'), findsNothing);
    });

    testWidgets('si GET /chat/read falla, el chat funciona igual y sin linea', (tester) async {
      final s = Servidor(inicial: _rango(1, 8))..lecturaFalla = true;
      await _abrir(tester, s);
      expect(find.text('mensaje 8'), findsOneWidget);
      expect(find.text('Mensajes nuevos'), findsNothing);
      expect(find.byType(SnackBar), findsNothing, reason: 'no molesta con avisos');
      expect(tester.widget<TextField>(find.byType(TextField)).enabled, isTrue);
    });

    testWidgets('lo que llega mientras miras NO pone la linea', (tester) async {
      final s = Servidor(inicial: _rango(1, 8), lastRead: 8);
      await _abrir(tester, s);
      s.nuevos.add(_m(9));
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('mensaje 9'), findsOneWidget);
      expect(find.text('Mensajes nuevos'), findsNothing);
    });

    testWidgets('con mas no leidos que la primera pagina, la linea espera (no salta al cargar)', (tester) async {
      final s = Servidor(inicial: _rango(51, 60), lastRead: 10)..inicialHasMore = true;
      await _abrir(tester, s);
      expect(find.text('Mensajes nuevos', skipOffstage: false), findsNothing);
    });
  });

  group('marcar como leido', () {
    testWidgets('al entrar y estar al final: PUT con el ultimo seq, despues de conocer lo leido', (tester) async {
      final s = Servidor(inicial: _rango(1, 8), lastRead: 4)..lecturaLenta = Completer();
      await _abrir(tester, s);
      await tester.pump(const Duration(seconds: 2));
      expect(s.puts, isEmpty, reason: 'sin el lastReadSeq no se marca (se perderia la linea)');

      s.lecturaLenta!.complete(_json({'lastReadSeq': 4, 'unreadCount': 4, 'lastSeq': 8}));
      await tester.pump();
      await tester.pump();
      expect(find.text('Mensajes nuevos'), findsOneWidget);
      await tester.pump(const Duration(seconds: 1));
      expect(s.puts, [8]);
      expect(find.text('Mensajes nuevos'), findsOneWidget, reason: 'la linea queda durante la visita');
    });

    testWidgets('al salir de la pantalla manda lo pendiente sin esperar', (tester) async {
      final s = Servidor(inicial: _rango(1, 8), lastRead: 8);
      await _abrir(tester, s);
      s.nuevos.add(_m(9));
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(s.puts, isEmpty, reason: 'todavia en el temporizador');
      await tester.pumpWidget(const MaterialApp(home: Text('otra pantalla')));
      await tester.pump();
      expect(s.puts, [9]);
    });

    testWidgets('en segundo plano no marca', (tester) async {
      final s = Servidor(inicial: _rango(1, 8), lastRead: 8);
      await _abrir(tester, s);
      for (final e in [AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]) {
        tester.binding.handleAppLifecycleStateChanged(e);
      }
      await tester.pump(const Duration(seconds: 5));
      expect(s.puts, isEmpty);
      for (final e in [AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]) {
        tester.binding.handleAppLifecycleStateChanged(e);
      }
      s.nuevos.add(_m(9));
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(seconds: 1));
      expect(s.puts, [9]);
    });
  });

  group('boton "N mensajes nuevos"', () {
    testWidgets('leyendo arriba: cuenta los de otros, no marca, y al tocarlo baja y marca', (tester) async {
      final s = Servidor(inicial: _rango(1, 40), lastRead: 40);
      await _abrir(tester, s);
      await tester.drag(_lista, const Offset(0, 900));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));

      s.nuevos.addAll([_m(41), _m(42), _m(43, autor: 'u1')]);
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('2 mensajes nuevos'), findsOneWidget, reason: 'el 43 es mio');
      await tester.pump(const Duration(seconds: 2));
      expect(s.puts, isEmpty, reason: 'no los viste: no se marcan');

      await tester.tap(find.text('2 mensajes nuevos'));
      for (var i = 0; i < 6; i++) {
        await tester.pump();
      }
      expect(find.textContaining('mensajes nuevos'), findsNothing);
      final lista = tester.getRect(_lista);
      expect(lista.contains(tester.getCenter(find.text('mensaje 43'))), isTrue);
      await tester.pump(const Duration(seconds: 1));
      expect(s.puts, [43]);
    });

    testWidgets('al final no aparece: baja solo', (tester) async {
      final s = Servidor(inicial: _rango(1, 40), lastRead: 40);
      await _abrir(tester, s);
      s.nuevos.add(_m(41));
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.textContaining('mensaje nuevo'), findsNothing);
    });

    testWidgets('accesibilidad: area de 48 dp y etiqueta', (tester) async {
      final handle = tester.ensureSemantics();
      final s = Servidor(inicial: _rango(1, 40), lastRead: 40);
      await _abrir(tester, s);
      await tester.drag(_lista, const Offset(0, 900));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      s.nuevos.add(_m(41));
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(find.text('1 mensaje nuevo'), findsOneWidget);
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      handle.dispose();
    });
  });
}
