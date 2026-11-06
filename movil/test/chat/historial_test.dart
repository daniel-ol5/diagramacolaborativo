import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/chat_merge.dart';
import 'package:movil/chat/historial.dart';
import 'package:movil/models/chat_message.dart';
import 'package:movil/services/chat_service.dart';

ChatMessage msg(int seq, {String? cmid}) =>
    ChatMessage.fromJson({'id': 'm$seq', 'seq': seq, 'clientMessageId': ?cmid, 'text': 't$seq'});

ChatPage pagina(List<int> seqs, {bool hasMore = false}) =>
    ChatPage(messages: [for (final s in seqs) msg(s)], hasMore: hasMore);

/// Servicio falso: responde lo que el test encole y anota los beforeSeq.
class Arnes {
  Arnes({List<int> iniciales = const [], bool hayMasViejos = true}) {
    timeline.fusionar([for (final s in iniciales) msg(s)]);
    historial = HistorialChat(
      traer: (beforeSeq) {
        pedidos.add(beforeSeq);
        if (respuestas.isEmpty) return Future.value(pagina([]));
        return respuestas.removeAt(0)();
      },
      primerSeq: () => timeline.primerSeq,
      alRecibir: timeline.fusionar,
      alCambiar: () => cambios++,
    )..inicializar(hayMasViejos: hayMasViejos);
  }

  final timeline = ChatTimeline();
  final pedidos = <int>[];
  final respuestas = <Future<ChatPage> Function()>[];
  int cambios = 0;
  late final HistorialChat historial;

  List<int> get seqs => [for (final m in timeline.mensajes) m.seq];
}

void main() {
  test('pide fetchBefore con el MENOR seq cargado y antepone ordenado', () async {
    final a = Arnes(iniciales: [50, 51, 53]);
    a.respuestas.add(() async => pagina([40, 42, 49], hasMore: true));
    expect(await a.historial.cargarAnteriores(), isTrue);
    expect(a.pedidos, [50]);
    expect(a.seqs, [40, 42, 49, 50, 51, 53]);
    expect(a.historial.hayMasViejos, isTrue);

    a.respuestas.add(() async => pagina([10, 20]));
    await a.historial.cargarAnteriores();
    expect(a.pedidos, [50, 40]);
    expect(a.seqs.first, 10);
  });

  test('se detiene cuando hasMore es false', () async {
    final a = Arnes(iniciales: [50]);
    a.respuestas.add(() async => pagina([1, 2]));
    await a.historial.cargarAnteriores();
    expect(a.historial.hayMasViejos, isFalse);
    expect(a.historial.puedeCargar, isFalse);
    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(a.pedidos.length, 1);
  });

  test('si la carga inicial dijo hasMore=false, no pide nada', () async {
    final a = Arnes(iniciales: [1, 2], hayMasViejos: false);
    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(a.pedidos, isEmpty);
  });

  test('sin mensajes cargados no hay cursor: no pide nada', () async {
    final a = Arnes();
    expect(a.historial.puedeCargar, isFalse);
    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(a.pedidos, isEmpty);
  });

  test('no lanza una segunda carga mientras hay una en curso', () async {
    final a = Arnes(iniciales: [50]);
    final lenta = Completer<ChatPage>();
    a.respuestas.add(() => lenta.future);
    final primera = a.historial.cargarAnteriores();
    expect(a.historial.cargando, isTrue);
    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(a.pedidos.length, 1);

    lenta.complete(pagina([40], hasMore: true));
    expect(await primera, isTrue);
    expect(a.historial.cargando, isFalse);
    expect(a.cambios, 2, reason: 'empieza y termina');
  });

  test('un fallo no pierde lo ya cargado y se puede reintentar', () async {
    final a = Arnes(iniciales: [50, 51]);
    a.respuestas.add(() async => pagina([40, 45], hasMore: true));
    await a.historial.cargarAnteriores();
    a.respuestas.add(() async => throw ChatException(0, 'Sin conexión con el servidor.'));

    expect(await a.historial.cargarAnteriores(), isFalse);
    expect(a.seqs, [40, 45, 50, 51]);
    expect(a.historial.ultimoError!.sinConexion, isTrue);
    expect(a.historial.hayMasViejos, isTrue);
    expect(a.historial.cargando, isFalse);

    a.respuestas.add(() async => pagina([30]));
    expect(await a.historial.cargarAnteriores(), isTrue);
    expect(a.pedidos, [50, 40, 40], reason: 'el reintento usa el mismo cursor');
    expect(a.historial.ultimoError, isNull);
    expect(a.seqs, [30, 40, 45, 50, 51]);
  });

  test('un error inesperado se envuelve en ChatException', () async {
    final a = Arnes(iniciales: [5]);
    a.respuestas.add(() async => throw StateError('bug'));
    await a.historial.cargarAnteriores();
    expect(a.historial.ultimoError!.status, -1);
  });

  test('hasMore=true pero sin nada anterior: deja de pedir (no se queda en bucle)', () async {
    final a = Arnes(iniciales: [50]);
    a.respuestas.add(() async => pagina([], hasMore: true));
    await a.historial.cargarAnteriores();
    expect(a.historial.hayMasViejos, isFalse);

    final b = Arnes(iniciales: [50]);
    b.respuestas.add(() async => pagina([50, 51], hasMore: true)); // solapa, no avanza
    await b.historial.cargarAnteriores();
    expect(b.historial.hayMasViejos, isFalse);
    expect(b.seqs, [50, 51]);
  });

  test('despues de dispose no se fusiona nada ni se avisa', () async {
    final a = Arnes(iniciales: [50]);
    final lenta = Completer<ChatPage>();
    a.respuestas.add(() => lenta.future);
    final carga = a.historial.cargarAnteriores();
    final cambiosAntes = a.cambios;
    a.historial.dispose();
    lenta.complete(pagina([40], hasMore: true));
    expect(await carga, isFalse);
    expect(a.seqs, [50]);
    expect(a.cambios, cambiosAntes);
    expect(a.historial.puedeCargar, isFalse);
  });
}
