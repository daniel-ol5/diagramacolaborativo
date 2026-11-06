import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/no_leidos.dart';
import 'package:movil/models/chat_message.dart';
import 'package:movil/models/chat_read_state.dart';
import 'package:movil/services/chat_service.dart';

ChatMessage m(int seq, {String? autor, String tipo = 'USER'}) =>
    ChatMessage.fromJson({'id': 'm$seq', 'seq': seq, 'authorType': tipo, 'authorId': ?autor});

ChatReadState estado(int lastRead) => ChatReadState(lastReadSeq: lastRead, unreadCount: 0, lastSeq: lastRead);

/// Temporizador que solo vence cuando el test lo dispara.
class TemporizadorFalso implements Timer {
  TemporizadorFalso(this._alVencer);
  final void Function() _alVencer;
  bool _activo = true;

  void vencer() {
    if (!_activo) return;
    _activo = false;
    _alVencer();
  }

  @override
  void cancel() => _activo = false;
  @override
  bool get isActive => _activo;
  @override
  int get tick => 0;
}

class Arnes {
  Arnes() {
    marcador = MarcadorLeido(
      enviar: (seq) {
        enviados.add(seq);
        if (respuestas.isEmpty) return Future.value(estado(seq));
        return respuestas.removeAt(0)();
      },
      alConfirmar: confirmados.add,
      crearTemporizador: (espera, alVencer) {
        final t = TemporizadorFalso(alVencer);
        timers.add(t);
        return t;
      },
    );
  }

  final enviados = <int>[];
  final confirmados = <ChatReadState>[];
  final respuestas = <Future<ChatReadState> Function()>[];
  final timers = <TemporizadorFalso>[];
  late final MarcadorLeido marcador;

  Iterable<TemporizadorFalso> get activos => timers.where((t) => t.isActive);
}

void main() {
  group('reglas', () {
    test('esDeOtro: lo propio no cuenta; IA y sistema (sin authorId) si', () {
      expect(esDeOtro(m(1, autor: 'u1'), 'u1'), isFalse);
      expect(esDeOtro(m(1, autor: 'u2'), 'u1'), isTrue);
      expect(esDeOtro(m(1, tipo: 'AI'), 'u1'), isTrue);
      expect(esDeOtro(m(1, tipo: 'SYSTEM'), 'u1'), isTrue);
    });

    test('seqLineaNuevos: el primer mensaje de otro despues de lo leido', () {
      final lista = [m(4, autor: 'u2'), m(5, autor: 'u1'), m(6, autor: 'u1'), m(8, tipo: 'AI'), m(9, autor: 'u2')];
      expect(seqLineaNuevos(lista, 4, 'u1'), 8, reason: 'salta los propios (5 y 6)');
      expect(seqLineaNuevos(lista, 0, 'u1'), 4);
      expect(seqLineaNuevos(lista, 9, 'u1'), isNull, reason: 'todo leido');
      expect(seqLineaNuevos([m(5, autor: 'u1')], 0, 'u1'), isNull, reason: 'solo propios');
      expect(seqLineaNuevos([], 0, 'u1'), isNull);
    });

    test('contarNuevos no cuenta los propios', () {
      expect(contarNuevos([m(1, autor: 'u2'), m(2, autor: 'u1'), m(3, tipo: 'AI')], 'u1'), 2);
      expect(contarNuevos([], 'u1'), 0);
    });

    test('textoContador y etiquetas', () {
      expect(textoContador(0), isNull);
      expect(textoContador(-3), isNull);
      expect(textoContador(1), '1');
      expect(textoContador(99), '99');
      expect(textoContador(100), '99+');
      expect(etiquetaIconoChat(0), 'Chat del proyecto');
      expect(etiquetaIconoChat(1), 'Chat del proyecto, 1 mensaje nuevo');
      expect(etiquetaIconoChat(7), 'Chat del proyecto, 7 mensajes nuevos');
      expect(etiquetaIconoChat(250), 'Chat del proyecto, más de 99 mensajes nuevos');
      expect(textoBotonNuevos(1), '1 mensaje nuevo');
      expect(textoBotonNuevos(4), '4 mensajes nuevos');
    });

    test('masReciente: gana el mayor lastReadSeq; si empatan, el mayor lastSeq', () {
      ChatReadState e(int leido, int ultimo, int sinLeer) =>
          ChatReadState(lastReadSeq: leido, unreadCount: sinLeer, lastSeq: ultimo);
      expect(masReciente(null, e(3, 8, 5)).unreadCount, 5);
      // Un GET que el servidor contesto ANTES del PUT de "lei todo" llega tarde:
      expect(masReciente(e(8, 8, 0), e(3, 8, 5)).unreadCount, 0);
      expect(masReciente(e(3, 8, 5), e(8, 8, 0)).unreadCount, 0);
      // Mismo lastReadSeq, llegaron mensajes nuevos:
      expect(masReciente(e(8, 8, 0), e(8, 10, 2)).unreadCount, 2);
      expect(masReciente(e(8, 10, 2), e(8, 8, 0)).unreadCount, 2);
    });

    test('ChatReadState.fromJson tolerante', () {
      final e = ChatReadState.fromJson({'lastReadSeq': 12, 'unreadCount': 3, 'lastSeq': 15});
      expect([e.lastReadSeq, e.unreadCount, e.lastSeq], [12, 3, 15]);
      final raro = ChatReadState.fromJson({'lastReadSeq': null, 'unreadCount': '4', 'lastSeq': -1});
      expect([raro.lastReadSeq, raro.unreadCount, raro.lastSeq], [0, 4, 0]);
      expect(ChatReadState.fromJson({}).unreadCount, 0);
    });
  });

  group('MarcadorLeido', () {
    test('agrupa varios avisos seguidos en un solo PUT con el mayor seq', () async {
      final a = Arnes();
      a.marcador
        ..quiero(5)
        ..quiero(7)
        ..quiero(9);
      expect(a.enviados, isEmpty);
      expect(a.activos.length, 1);
      a.activos.single.vencer();
      await pumpEventQueue();
      expect(a.enviados, [9]);
      expect(a.marcador.confirmado, 9);
      expect(a.confirmados.single.lastReadSeq, 9);
    });

    test('pedir el MISMO seq otra vez no posterga el envio; uno mayor si reprograma', () async {
      final a = Arnes();
      a.marcador.quiero(7);
      final primero = a.activos.single;
      a.marcador
        ..quiero(7)
        ..quiero(7);
      expect(a.activos.single, same(primero), reason: 'sigue el mismo temporizador');
      a.marcador.quiero(9);
      expect(primero.isActive, isFalse);
      a.activos.single.vencer();
      await pumpEventQueue();
      expect(a.enviados, [9]);
    });

    test('nunca pide un seq menor o igual al ya leido', () async {
      final a = Arnes()..marcador.inicializar(10);
      a.marcador
        ..quiero(8)
        ..quiero(10);
      expect(a.timers, isEmpty);
      await a.marcador.enviarAhora();
      expect(a.enviados, isEmpty);
    });

    test('nunca hay dos PUT a la vez; lo que llega durante uno se manda despues', () async {
      final a = Arnes();
      final lento = Completer<ChatReadState>();
      a.respuestas.add(() => lento.future);
      a.marcador.quiero(5);
      a.activos.single.vencer();
      expect(a.marcador.enCurso, isTrue);

      a.marcador.quiero(8);
      await a.marcador.enviarAhora();
      expect(a.enviados, [5], reason: 'el segundo espera al primero');

      lento.complete(estado(5));
      await pumpEventQueue();
      expect(a.activos.length, 1, reason: 'queda programado el 8');
      a.activos.single.vencer();
      await pumpEventQueue();
      expect(a.enviados, [5, 8]);
      expect(a.marcador.confirmado, 8);
    });

    test('si el PUT falla no reintenta solo y no avisa; se manda en la proxima oportunidad', () async {
      final a = Arnes();
      a.respuestas.add(() async => throw ChatException(0, 'Sin conexión con el servidor.'));
      a.marcador.quiero(5);
      a.activos.single.vencer();
      await pumpEventQueue();
      expect(a.enviados, [5]);
      expect(a.marcador.confirmado, 0);
      expect(a.marcador.pendiente, 5);
      expect(a.activos, isEmpty, reason: 'sin reintento automatico');
      expect(a.confirmados, isEmpty);

      a.marcador.quiero(5); // misma posicion: vuelve a intentar
      a.activos.single.vencer();
      await pumpEventQueue();
      expect(a.enviados, [5, 5]);
      expect(a.marcador.confirmado, 5);
      expect(a.marcador.pendiente, isNull);
    });

    test('enviarAhora (al salir) manda lo pendiente sin esperar el temporizador', () async {
      final a = Arnes();
      a.marcador.quiero(12);
      final estado = await a.marcador.enviarAhora();
      expect(a.enviados, [12]);
      expect(a.activos, isEmpty);
      expect(estado!.lastReadSeq, 12, reason: 'devuelve lo confirmado');
      expect(await a.marcador.enviarAhora(), isNull, reason: 'nada pendiente');
    });

    test('enviarAhora devuelve el estado aunque el marcador se cierre durante el envio', () async {
      final a = Arnes();
      final lento = Completer<ChatReadState>();
      a.respuestas.add(() => lento.future);
      a.marcador.quiero(7);
      final envio = a.marcador.enviarAhora();
      a.marcador.dispose();
      lento.complete(estado(7));
      expect((await envio)!.lastReadSeq, 7);
      expect(a.confirmados, isEmpty, reason: 'el callback de la pantalla ya no se llama');
    });

    test('el servidor recorta a lastSeq: no vuelve a pedir lo mismo', () async {
      final a = Arnes();
      a.respuestas.add(() async => estado(45));
      a.marcador.quiero(50);
      await a.marcador.enviarAhora();
      a.marcador.quiero(50);
      expect(a.activos, isEmpty);
      expect(a.enviados, [50]);
    });

    test('despues de dispose no programa ni avisa', () async {
      final a = Arnes();
      final lento = Completer<ChatReadState>();
      a.respuestas.add(() => lento.future);
      a.marcador.quiero(5);
      final envio = a.marcador.enviarAhora();
      a.marcador.dispose();
      lento.complete(estado(5));
      await envio;
      a.marcador.quiero(9);
      expect(a.confirmados, isEmpty);
      expect(a.activos, isEmpty);
      expect(a.enviados, [5]);
    });
  });
}
