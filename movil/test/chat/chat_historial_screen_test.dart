import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:movil/chat/separadores_dia.dart';
import 'package:movil/services/chat_service.dart';
import 'package:movil/services/config_service.dart';
import 'package:movil/ui/chat_screen.dart';

// CU23 en la pantalla: cargar anteriores conservando la posicion.

const _sesion = Sesion(
  token: 't',
  userId: 'u1',
  email: 'ana@x.com',
  nombreCompleto: 'Ana',
  direccionServidor: 'http://servidor',
);

final _fechaFija = DateTime.utc(2026, 11, 5, 14);

Map<String, dynamic> _m(int seq, [DateTime? fecha]) => {
      'id': 'm$seq',
      'projectId': 'p1',
      'seq': seq,
      'authorType': 'USER',
      'authorId': 'u2',
      'authorName': 'Beto',
      'kind': 'CHAT',
      'text': 'mensaje $seq',
      'createdAt': (fecha ?? _fechaFija).toUtc().toIso8601String(),
    };

http.Response _pagina(Iterable<int> seqs, {bool hasMore = false, DateTime? fecha}) => http.Response(
      jsonEncode({'messages': [for (final s in seqs) _m(s, fecha)], 'hasMore': hasMore}),
      200,
      headers: {'content-type': 'application/json'},
    );

Iterable<int> _rango(int desde, int hasta) => [for (var i = desde; i <= hasta; i++) i];

/// [antes] contesta los GET con beforeSeq; [despues] los de afterSeq.
Future<List<String>> _abrir(
  WidgetTester tester, {
  required http.Response inicial,
  Future<http.Response> Function(String beforeSeq)? antes,
  Future<http.Response> Function(String afterSeq)? despues,
}) async {
  final cursoresAntes = <String>[];
  final servicio = ChatService(
    direccionServidor: 'http://servidor',
    token: 't',
    client: MockClient((r) async {
      final q = r.url.queryParameters;
      if (q['beforeSeq'] != null) {
        cursoresAntes.add(q['beforeSeq']!);
        return antes == null ? _pagina([]) : antes(q['beforeSeq']!);
      }
      if (q['afterSeq'] != null) return despues == null ? _pagina([]) : despues(q['afterSeq']!);
      return inicial;
    }),
  );
  await tester.pumpWidget(MaterialApp(
    home: ChatScreen(sesion: _sesion, projectId: 'p1', projectName: 'Tienda', servicio: servicio),
  ));
  await tester.pump();
  await tester.pump();
  return cursoresAntes;
}

final _lista = find.byType(CustomScrollView);

ScrollPosition _posicion(WidgetTester tester) =>
    tester.state<ScrollableState>(find.descendant(of: _lista, matching: find.byType(Scrollable))).position;

/// Desliza hacia el tope (el dedo baja) y deja terminar la inercia.
Future<void> _subir(WidgetTester tester, [double cuanto = 5000]) async {
  await tester.drag(_lista, Offset(0, cuanto));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 800));
}

void main() {
  testWidgets('al llegar arriba pide beforeSeq = menor seq y CONSERVA la posicion', (tester) async {
    final respuesta = Completer<http.Response>();
    final cursores = await _abrir(
      tester,
      inicial: _pagina(_rango(51, 80), hasMore: true),
      antes: (_) => respuesta.future,
    );

    await _subir(tester);
    expect(cursores, ['51']);
    expect(find.bySemanticsLabel('Cargando mensajes anteriores'), findsOneWidget);
    final antes = tester.getTopLeft(find.text('mensaje 51')).dy;

    respuesta.complete(_pagina(_rango(21, 50), hasMore: true));
    await tester.pump();
    await tester.pump();

    expect(tester.getTopLeft(find.text('mensaje 51')).dy, moreOrLessEquals(antes, epsilon: 0.5));
    expect(find.text('mensaje 50'), findsOneWidget, reason: 'lo anterior queda justo arriba');
    // Todo es del mismo dia: el separador se mudo al tope de lo cargado
    // (fuera de la vista) y no queda uno repetido encima de "mensaje 51".
    final etiqueta = etiquetaDia(_fechaFija, DateTime.now());
    expect(find.text(etiqueta), findsNothing);
    expect(find.bySemanticsLabel('Cargando mensajes anteriores'), findsNothing);
  });

  testWidgets('no lanza una segunda carga mientras hay una en curso', (tester) async {
    final respuesta = Completer<http.Response>();
    final cursores = await _abrir(
      tester,
      inicial: _pagina(_rango(51, 80), hasMore: true),
      antes: (_) => respuesta.future,
    );
    await _subir(tester);
    await _subir(tester);
    await _subir(tester);
    expect(cursores, ['51']);
    respuesta.complete(_pagina(_rango(21, 50)));
    await tester.pump();
  });

  testWidgets('con hasMore=false no pide nada y muestra el inicio de la conversacion', (tester) async {
    final cursores = await _abrir(tester, inicial: _pagina(_rango(1, 30)));
    await _subir(tester);
    expect(cursores, isEmpty);
    expect(find.text('Inicio de la conversación'), findsOneWidget);
    expect(find.text('Cargar anteriores'), findsNothing);
  });

  testWidgets('un fallo avisa con Reintentar, no reintenta solo y no pierde lo cargado', (tester) async {
    var intentos = 0;
    final cursores = await _abrir(
      tester,
      inicial: _pagina(_rango(51, 80), hasMore: true),
      antes: (_) async => ++intentos == 1 ? http.Response('', 500) : _pagina(_rango(21, 50)),
    );
    await _subir(tester);
    expect(find.text('No se pudieron cargar los mensajes anteriores. El servidor respondió 500.'), findsOneWidget);
    expect(find.text('mensaje 51'), findsOneWidget);
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, isTrue, reason: 'el chat sigue funcionando');

    await _subir(tester);
    expect(cursores.length, 1, reason: 'despues de un fallo no reintenta solo');

    await tester.tap(find.text('Reintentar'));
    await tester.pump();
    await tester.pump();
    expect(cursores, ['51', '51']);
    expect(find.textContaining('No se pudieron cargar'), findsNothing);
    expect(find.text('mensaje 50'), findsOneWidget);
  });

  testWidgets('el boton "Cargar anteriores" (alternativa accesible) pide la pagina', (tester) async {
    // Pocos mensajes: la cabecera queda construida justo ARRIBA de la vista
    // (fuera de pantalla), por eso skipOffstage: false.
    final cursores = await _abrir(tester, inicial: _pagina(_rango(78, 80), hasMore: true));
    final boton = find.ancestor(
      of: find.text('Cargar anteriores', skipOffstage: false),
      matching: find.byWidgetPredicate((w) => w is TextButton, skipOffstage: false),
    );
    expect(boton, findsOneWidget);
    expect(tester.getSize(boton).height, greaterThanOrEqualTo(48));
    expect(cursores, isEmpty);

    tester.widget<TextButton>(boton).onPressed!();
    await tester.pump();
    await tester.pump();
    expect(cursores, ['78']);
  });

  testWidgets('el sondeo no mueve la posicion si estoy leyendo mensajes viejos', (tester) async {
    var nuevos = false;
    await _abrir(
      tester,
      inicial: _pagina(_rango(1, 40)),
      despues: (_) async => nuevos ? _pagina(_rango(41, 45)) : _pagina([]),
    );
    await _subir(tester, 900);
    final pixels = _posicion(tester).pixels;
    expect(pixels, lessThan(_posicion(tester).maxScrollExtent - 100), reason: 'el usuario subio');
    final rect = tester.getRect(_lista);
    final enPantalla = _rango(1, 40).map((s) => find.text('mensaje $s')).firstWhere(
          (f) => f.evaluate().isNotEmpty && rect.contains(tester.getCenter(f)),
        );
    final antes = tester.getTopLeft(enPantalla).dy;

    nuevos = true;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    await tester.pump();
    expect(_posicion(tester).pixels, pixels);
    expect(tester.getTopLeft(enPantalla).dy, antes);
  });

  testWidgets('al final, lo que trae el sondeo si se ve (auto-scroll)', (tester) async {
    var nuevos = false;
    await _abrir(
      tester,
      inicial: _pagina(_rango(1, 40)),
      despues: (_) async => nuevos ? _pagina([41]) : _pagina([]),
    );
    nuevos = true;
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    await tester.pump();
    final pos = _posicion(tester);
    expect(pos.pixels, pos.maxScrollExtent);
    final lista = tester.getRect(_lista);
    expect(lista.contains(tester.getCenter(find.text('mensaje 41'))), isTrue);
  });

  testWidgets('al abrir llega al final REAL aunque haya separadores (alturas distintas)', (tester) async {
    final ahora = DateTime.now();
    final ayer = DateTime(ahora.year, ahora.month, ahora.day - 1, 12);
    await _abrir(
      tester,
      inicial: http.Response(
        jsonEncode({
          'messages': [for (var i = 1; i <= 30; i++) _m(i, i <= 15 ? ayer : ahora)],
          'hasMore': false,
        }),
        200,
        headers: {'content-type': 'application/json'},
      ),
    );
    for (var i = 0; i < 5; i++) {
      await tester.pump();
    }
    // El salto va al maximo ESTIMADO; si el real resulta menor, Flutter
    // vuelve solo al final con la animacion de rebote (unos ms).
    await tester.pump(const Duration(seconds: 1));
    final pos = _posicion(tester);
    expect(pos.pixels, moreOrLessEquals(pos.maxScrollExtent, epsilon: 0.5));
    final lista = tester.getRect(_lista);
    final ultimo = tester.getRect(find.text('mensaje 30'));
    expect(ultimo.bottom, lessThanOrEqualTo(lista.bottom), reason: 'el ultimo mensaje se ve entero');
  });

  group('separadores de dia', () {
    testWidgets('Hoy, Ayer y fecha, en orden y con Semantics', (tester) async {
      final ahora = DateTime.now();
      final ayer = DateTime(ahora.year, ahora.month, ahora.day - 1, 12);
      final viejo = DateTime(2025, 3, 9, 12);
      final handle = tester.ensureSemantics();
      await _abrir(
        tester,
        inicial: http.Response(
          jsonEncode({
            'messages': [_m(1, viejo), _m(2, ayer), _m(3, ahora), _m(4, ahora)],
            'hasMore': false,
          }),
          200,
          headers: {'content-type': 'application/json'},
        ),
      );
      for (final t in ['9 mar 2025', 'Ayer', 'Hoy']) {
        expect(find.text(t), findsOneWidget, reason: t);
      }
      double y(String t) => tester.getTopLeft(find.text(t)).dy;
      final orden = ['9 mar 2025', 'mensaje 1', 'Ayer', 'mensaje 2', 'Hoy', 'mensaje 3', 'mensaje 4'];
      for (var i = 1; i < orden.length; i++) {
        expect(y(orden[i - 1]), lessThan(y(orden[i])), reason: '${orden[i - 1]} antes que ${orden[i]}');
      }
      // Centrado horizontalmente en la lista.
      expect(tester.getCenter(find.text('Hoy')).dx, moreOrLessEquals(tester.getCenter(_lista).dx, epsilon: 1));
      expect(find.bySemanticsLabel('Día: Hoy'), findsOneWidget);
      expect(tester.getSemantics(find.bySemanticsLabel('Día: Ayer')), isSemantics(label: 'Día: Ayer', isHeader: true));
      handle.dispose();
    });

    testWidgets('al anteponer mensajes de otro dia: su separador aparece y la vista no se mueve', (tester) async {
      final ahora = DateTime.now();
      final ayer = DateTime(ahora.year, ahora.month, ahora.day - 1, 12);
      final respuesta = Completer<http.Response>();
      await _abrir(
        tester,
        inicial: _pagina(_rango(51, 80), hasMore: true, fecha: ahora),
        antes: (_) => respuesta.future,
      );
      await _subir(tester);
      final antes51 = tester.getTopLeft(find.text('mensaje 51')).dy;
      final antesHoy = tester.getTopLeft(find.text('Hoy')).dy;

      respuesta.complete(_pagina(_rango(21, 50), fecha: ayer));
      await tester.pump();
      await tester.pump();

      expect(tester.getTopLeft(find.text('mensaje 51')).dy, moreOrLessEquals(antes51, epsilon: 0.5));
      expect(tester.getTopLeft(find.text('Hoy')).dy, moreOrLessEquals(antesHoy, epsilon: 0.5));
      expect(find.text('Hoy'), findsOneWidget);
      // "Ayer" queda arriba de los 30 mensajes de ayer: se ve al subir.
      await _subir(tester);
      expect(find.text('Ayer'), findsOneWidget);
      expect(tester.getTopLeft(find.text('Ayer')).dy, lessThan(tester.getTopLeft(find.text('mensaje 21')).dy));
    });
  });
}
