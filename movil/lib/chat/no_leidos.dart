import 'dart:async';

import '../models/chat_message.dart';
import '../models/chat_read_state.dart';
import '../services/chat_service.dart';

/// Reglas de "mensajes nuevos" del chat en el movil (CU23), sin widgets.
/// Mismo criterio que la web (web/src/assistant/chat/unread.ts) y que el
/// unreadCount del servidor: lo propio nunca cuenta; la IA y el sistema si.

/// Un mensaje es "de otro" si no tiene authorId (IA, SYSTEM) o es de otra
/// persona.
bool esDeOtro(ChatMessage m, String miUserId) => m.authorId == null || m.authorId != miUserId;

/// Seq del mensaje encima del cual va la linea "Mensajes nuevos": el
/// primero de otro con seq > [lastReadSeq] entre los cargados. null si no
/// hay ninguno (no se dibuja la linea). [mensajes] ordenados por seq.
int? seqLineaNuevos(List<ChatMessage> mensajes, int lastReadSeq, String miUserId) {
  for (final m in mensajes) {
    if (m.seq > lastReadSeq && esDeOtro(m, miUserId)) return m.seq;
  }
  return null;
}

/// Cuantos de los mensajes que acaban de llegar suman al boton "N mensajes
/// nuevos" (solo se usa mientras el usuario NO esta mirando el final).
int contarNuevos(Iterable<ChatMessage> llegados, String miUserId) =>
    llegados.where((m) => esDeOtro(m, miUserId)).length;

/// Texto del contador del icono: null si no hay (no se muestra), "99+" si
/// pasa de 99.
String? textoContador(int noLeidos) {
  if (noLeidos <= 0) return null;
  return noLeidos > 99 ? '99+' : '$noLeidos';
}

/// Lo que lee TalkBack en el icono del chat.
String etiquetaIconoChat(int noLeidos) {
  if (noLeidos <= 0) return 'Chat del proyecto';
  if (noLeidos == 1) return 'Chat del proyecto, 1 mensaje nuevo';
  return 'Chat del proyecto, ${noLeidos > 99 ? 'más de 99' : noLeidos} mensajes nuevos';
}

/// De dos estados de lectura del servidor, el mas reciente: mayor
/// lastReadSeq (en el servidor solo avanza) y, si empatan, mayor lastSeq.
/// Asi una respuesta que llega tarde (un GET que el servidor contesto
/// antes que el PUT de "lei todo") no pisa a una mas nueva.
ChatReadState masReciente(ChatReadState? actual, ChatReadState nuevo) {
  if (actual == null) return nuevo;
  if (nuevo.lastReadSeq != actual.lastReadSeq) return nuevo.lastReadSeq > actual.lastReadSeq ? nuevo : actual;
  return nuevo.lastSeq >= actual.lastSeq ? nuevo : actual;
}

/// Texto del boton flotante dentro del chat.
String textoBotonNuevos(int n) => n == 1 ? '1 mensaje nuevo' : '$n mensajes nuevos';

typedef CrearTemporizador = Timer Function(Duration espera, void Function() alVencer);

/// Marca como leido (PUT /chat/read) sin mandar un pedido por cada mensaje.
///
/// - [quiero] anota hasta que seq se leyo y programa el envio despues de
///   [espera] (agrupa varios avisos seguidos en un solo PUT).
/// - [enviarAhora] manda ya lo pendiente (al salir de la pantalla).
/// - Nunca hay dos PUT a la vez; si llega algo mayor durante uno, se manda
///   despues. Nunca pide un seq menor o igual al ya confirmado.
/// - Si el PUT falla, no reintenta solo: queda pendiente para el proximo
///   [quiero] o [enviarAhora]. No molesta con avisos.
class MarcadorLeido {
  MarcadorLeido({
    required this.enviar,
    this.alConfirmar,
    this.espera = const Duration(seconds: 1),
    CrearTemporizador? crearTemporizador,
  }) : _crearTemporizador = crearTemporizador ?? Timer.new;

  /// Normalmente `(seq) => servicio.markRead(projectId, seq)`.
  final Future<ChatReadState> Function(int upToSeq) enviar;
  final void Function(ChatReadState estado)? alConfirmar;
  final Duration espera;
  final CrearTemporizador _crearTemporizador;

  int _confirmado = 0;
  int? _pendiente;
  bool _enCurso = false;
  bool _cerrado = false;
  Timer? _timer;

  int get confirmado => _confirmado;
  int? get pendiente => _pendiente;
  bool get enCurso => _enCurso;

  /// Con el lastReadSeq que dio el servidor al entrar.
  void inicializar(int lastReadSeq) {
    if (lastReadSeq > _confirmado) _confirmado = lastReadSeq;
  }

  void quiero(int seq) {
    if (_cerrado || seq <= _confirmado) return;
    final mayor = _pendiente == null || seq > _pendiente!;
    if (mayor) _pendiente = seq;
    // Si hay un PUT en vuelo, al terminar se encarga de lo pendiente.
    if (_enCurso) return;
    // Pedir lo MISMO otra vez (scroll, sondeo) no posterga el envio: si no,
    // una seguidilla de avisos iguales lo podria demorar para siempre.
    if (!mayor && (_timer?.isActive ?? false)) return;
    _timer?.cancel();
    _timer = _crearTemporizador(espera, enviarAhora);
  }

  /// Devuelve el estado que confirmo el servidor, o null si no habia nada
  /// que mandar o fallo. (Al salir de la pantalla sirve para avisar el
  /// contador nuevo aunque el marcador ya este cerrado.)
  Future<ChatReadState?> enviarAhora() async {
    _timer?.cancel();
    _timer = null;
    final seq = _pendiente;
    if (_enCurso || seq == null || seq <= _confirmado) return null;
    _enCurso = true;
    var salioBien = false;
    ChatReadState? confirmado;
    try {
      final estado = await enviar(seq);
      salioBien = true;
      confirmado = estado;
      if (estado.lastReadSeq > _confirmado) _confirmado = estado.lastReadSeq;
      // El servidor recorta a lastSeq: si confirmo menos de lo pedido,
      // pedirlo de nuevo daria lo mismo.
      if (seq > _confirmado) _confirmado = seq;
      if (_pendiente != null && _pendiente! <= _confirmado) _pendiente = null;
      if (!_cerrado) alConfirmar?.call(estado);
    } on ChatException {
      // Queda pendiente: se manda en la proxima oportunidad.
    } catch (_) {
      // Idem: un error inesperado no debe romper la pantalla.
    } finally {
      _enCurso = false;
    }
    // Llego algo mayor mientras se enviaba (tras un fallo NO: espera la
    // proxima oportunidad, sin reintentar solo).
    if (salioBien && !_cerrado && _pendiente != null && _pendiente! > _confirmado) {
      _timer = _crearTemporizador(espera, enviarAhora);
    }
    return confirmado;
  }

  /// Corta el temporizador. Llamar [enviarAhora] antes si se quiere guardar
  /// lo pendiente (al salir de la pantalla).
  void dispose() {
    _cerrado = true;
    _timer?.cancel();
    _timer = null;
  }
}
