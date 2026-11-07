import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:movil/chat/chat_merge.dart';
import 'package:movil/chat/chat_poller.dart';
import 'package:movil/models/chat_message.dart';
import 'package:movil/services/chat_service.dart';

ChatMessage msg(String id, int seq) => ChatMessage.fromJson({'id': id, 'seq': seq, 'text': id});

ChatPage pagina(List<ChatMessage> m, {bool hasMore = false}) => ChatPage(messages: m, hasMore: hasMore);

/// Timer que solo "suena" cuando el test lo dispara.
class TimerFalso implements Timer {
  TimerFalso(this._alSonar);

  final void Function(Timer) _alSonar;
  bool _activo = true;
  int _ticks = 0;

  void sonar() {
    if (!_activo) return;
    _ticks++;
    _alSonar(this);
  }

  @override
  void cancel() => _activo = false;
  @override
  bool get isActive => _activo;
  @override
  int get tick => _ticks;
}

/// Servicio falso: responde con lo que el test encole y anota los cursores.
class ServicioFalso {
  final pedidos = <int>[];
  final respuestas = <FutureOr<ChatPage> Function()>[];

  Future<ChatPage> traer(int afterSeq) async {
    pedidos.add(afterSeq);
    if (respuestas.isEmpty) return pagina([]);
    return respuestas.removeAt(0)();
  }
}

class Arnes {
  Arnes({int maxIteraciones = 5}) {
    poller = ChatPoller(
      traer: servicio.traer,
      ultimoSeq: () => timeline.ultimoSeq,
      alRecibir: (m) {
        recibidos++;
        timeline.fusionar(m);
      },
      alFallar: errores.add,
      maxIteraciones: maxIteraciones,
      crearTimer: (intervalo, alSonar) {
        intervaloUsado = intervalo;
        final t = TimerFalso(alSonar);
        timers.add(t);
        return t;
      },
    );
  }

  final servicio = ServicioFalso();
  final timeline = ChatTimeline();
  final errores = <ChatException>[];
  final timers = <TimerFalso>[];
  Duration? intervaloUsado;
  int recibidos = 0;
  late final ChatPoller poller;

  List<String> get ids => [for (final m in timeline.mensajes) m.id];
}

void main() {
  test('cada 3 s: el timer dispara un ciclo con fetchAfter(ultimo seq)', () async {
    final a = Arnes();
    a.timeline.fusionar([msg('a', 4)]);
    a.poller.iniciar();
    expect(a.intervaloUsado, const Duration(seconds: 3));
    expect(a.servicio.pedidos, isEmpty);

    a.servicio.respuestas.add(() => pagina([msg('b', 5)]));
    a.timers.single.sonar();
    await pumpEventQueue();
    expect(a.servicio.pedidos, [4]);
    expect(a.ids, ['a', 'b']);
  });

  test('hasMore=true repite en el mismo ciclo, avanzando el cursor', () async {
    final a = Arnes();
    a.servicio.respuestas
      ..add(() => pagina([msg('a', 1), msg('b', 2)], hasMore: true))
      ..add(() => pagina([msg('c', 5)], hasMore: true))
      ..add(() => pagina([msg('d', 6)]));
    await a.poller.ciclo();
    expect(a.servicio.pedidos, [0, 2, 5]);
    expect(a.ids, ['a', 'b', 'c', 'd']);
  });

  test('hasMore=true tiene un tope de iteraciones por ciclo', () async {
    final a = Arnes(maxIteraciones: 3);
    var seq = 0;
    for (var i = 0; i < 10; i++) {
      a.servicio.respuestas.add(() => pagina([msg('m${++seq}', seq)], hasMore: true));
    }
    await a.poller.ciclo();
    expect(a.servicio.pedidos.length, 3);
  });

  test('hasMore=true sin que avance el cursor no se repite para siempre', () async {
    final a = Arnes();
    a.timeline.fusionar([msg('a', 9)]);
    a.servicio.respuestas
      ..add(() => pagina([msg('a', 9)], hasMore: true))
      ..add(() => pagina([msg('a', 9)], hasMore: true));
    await a.poller.ciclo();
    expect(a.servicio.pedidos, [9]);
  });

  test('no duplica: paginas que se solapan con lo que ya habia', () async {
    final a = Arnes();
    a.timeline.fusionar([msg('a', 1), msg('b', 2)]);
    a.servicio.respuestas
      ..add(() => pagina([msg('b', 2), msg('c', 3)], hasMore: true))
      ..add(() => pagina([msg('c', 3), msg('d', 4)]));
    await a.poller.ciclo();
    await a.poller.ciclo(); // la respuesta por defecto viene vacia
    expect(a.ids, ['a', 'b', 'c', 'd']);
  });

  test('nunca corren dos ciclos a la vez', () async {
    final a = Arnes();
    final lento = Completer<ChatPage>();
    a.servicio.respuestas.add(() => lento.future);
    a.poller.iniciar();
    final primero = a.poller.ciclo();
    a.timers.single.sonar(); // el timer suena mientras el primero sigue en vuelo
    await a.poller.ciclo();
    expect(a.servicio.pedidos.length, 1);
    lento.complete(pagina([msg('a', 1)]));
    await primero;
    expect(a.poller.enCurso, isFalse);
  });

  test('pausar corta el timer y descarta lo que estaba en vuelo', () async {
    final a = Arnes();
    final lento = Completer<ChatPage>();
    a.servicio.respuestas.add(() => lento.future);
    a.poller.iniciar();
    final ciclo = a.poller.ciclo();
    a.poller.pausar();
    expect(a.poller.activo, isFalse);
    expect(a.timers.single.isActive, isFalse);

    lento.complete(pagina([msg('a', 1)]));
    await ciclo;
    expect(a.recibidos, 0);
    expect(a.ids, isEmpty);

    a.timers.single.sonar(); // cancelado: no hace nada
    await pumpEventQueue();
    expect(a.servicio.pedidos.length, 1);
  });

  test('al reanudar: un timer nuevo y un ciclo inmediato', () async {
    final a = Arnes();
    a.poller.iniciar();
    a.poller.iniciar(); // dos veces no crea dos timers
    expect(a.timers.length, 1);
    a.poller.pausar();
    a.poller.iniciar(inmediato: true);
    await pumpEventQueue();
    expect(a.timers.length, 2);
    expect(a.servicio.pedidos.length, 1);
  });

  test('dispose: no vuelve a arrancar ni a llamar callbacks', () async {
    final a = Arnes();
    final lento = Completer<ChatPage>();
    a.servicio.respuestas.add(() => lento.future);
    a.poller.iniciar();
    final ciclo = a.poller.ciclo();
    a.poller.dispose();
    lento.complete(pagina([msg('a', 1)]));
    await ciclo;
    a.poller.iniciar();
    await a.poller.ciclo();
    expect(a.recibidos, 0);
    expect(a.timers.length, 1);
    expect(a.servicio.pedidos.length, 1);
  });

  test('un error llega a alFallar; uno inesperado se envuelve en ChatException', () async {
    final a = Arnes();
    a.servicio.respuestas
      ..add(() => throw ChatException(0, 'Sin conexión con el servidor.'))
      ..add(() => throw StateError('bug'));
    await a.poller.ciclo();
    await a.poller.ciclo();
    expect(a.errores.map((e) => e.status), [0, -1]);
    expect(a.errores.first.sinConexion, isTrue);
  });
}
