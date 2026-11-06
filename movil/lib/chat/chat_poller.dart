import 'dart:async';

import '../models/chat_message.dart';
import '../services/chat_service.dart';

typedef CrearTimer = Timer Function(Duration intervalo, void Function(Timer) alSonar);

/// Sondeo del chat sin widgets (CU19 va por REST, sin STOMP).
///
/// Cada [intervalo] pide lo posterior al ultimo seq conocido. Si el
/// servidor contesta hasMore=true, vuelve a pedir en el acto en vez de
/// esperar al proximo ciclo, hasta [maxIteraciones] por ciclo (para no
/// quedar atrapado si algo anda mal). Nunca corren dos ciclos a la vez.
///
/// [pausar] corta el timer e IGNORA lo que llegue de un pedido que ya
/// estaba en vuelo: despues de pausar (segundo plano) o de [dispose]
/// (salir de la pantalla) no se llama a ningun callback.
class ChatPoller {
  ChatPoller({
    required this.traer,
    required this.ultimoSeq,
    required this.alRecibir,
    required this.alFallar,
    this.intervalo = const Duration(seconds: 3),
    this.maxIteraciones = 5,
    CrearTimer? crearTimer,
  }) : _crearTimer = crearTimer ?? Timer.periodic;

  /// Normalmente `(seq) => servicio.fetchAfter(projectId, seq)`.
  final Future<ChatPage> Function(int afterSeq) traer;

  /// El seq mas alto que ya tiene la pantalla (null si no hay mensajes).
  final int? Function() ultimoSeq;

  /// Se llama con cada pagina recibida, aunque venga vacia: un pedido que
  /// salio bien tambien significa "volvio la conexion". Tiene que fusionar
  /// los mensajes en el acto, porque el siguiente pedido usa [ultimoSeq].
  final void Function(List<ChatMessage> mensajes) alRecibir;
  final void Function(ChatException error) alFallar;

  final Duration intervalo;
  final int maxIteraciones;
  final CrearTimer _crearTimer;

  Timer? _timer;
  bool _enCurso = false;
  bool _cerrado = false;

  /// Cambia al pausar: un pedido de una generacion vieja se descarta.
  int _generacion = 0;

  bool get activo => _timer != null;
  bool get enCurso => _enCurso;

  /// Arranca el timer (si no estaba). Con [inmediato], ademas hace un
  /// ciclo ya, por ejemplo al volver del segundo plano.
  void iniciar({bool inmediato = false}) {
    if (_cerrado || _timer != null) return;
    _timer = _crearTimer(intervalo, (_) => ciclo());
    if (inmediato) ciclo();
  }

  void pausar() {
    _timer?.cancel();
    _timer = null;
    _generacion++;
  }

  void dispose() {
    pausar();
    _cerrado = true;
  }

  /// Un ciclo: pide, y mientras hasMore sea true, vuelve a pedir.
  Future<void> ciclo() async {
    if (_cerrado || _enCurso) return;
    _enCurso = true;
    final gen = _generacion;
    try {
      for (var i = 0; i < maxIteraciones; i++) {
        final antes = ultimoSeq();
        final pagina = await traer(antes ?? 0);
        if (gen != _generacion || _cerrado) return;
        alRecibir(pagina.messages);
        if (!pagina.hasMore || pagina.messages.isEmpty) break;
        // Si el cursor no avanzo, repetir pediria lo mismo para siempre.
        if (ultimoSeq() == antes) break;
      }
    } catch (e) {
      if (gen != _generacion || _cerrado) return;
      alFallar(e is ChatException ? e : ChatException(-1, 'Ocurrió un error al actualizar el chat.'));
    } finally {
      _enCurso = false;
    }
  }
}
